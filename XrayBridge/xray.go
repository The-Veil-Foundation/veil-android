// Package xray embeds Xray-core into an iOS NetworkExtension.
//
// The whole tunnel lives inside Xray: the packet tunnel provider hands us the
// utun file descriptor it got from NetworkExtension, we publish it through the
// `xray.tun.fd` environment flag, and Xray's native `tun` inbound (gVisor based)
// terminates TCP/UDP straight off the interface. There is no tun2socks, no
// second core and no local SOCKS hop — packets go utun -> Xray -> server.
//
// Built with `gomobile bind` into XrayCore.xcframework, see
// Scripts/ios/build-xraycore.sh.
package xray

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"

	"github.com/xtls/xray-core/app/observatory"
	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/extension"
	"github.com/xtls/xray-core/features/stats"
	"github.com/xtls/xray-core/infra/conf/serial"

	// Registers every protocol, transport and JSON parser. Importing the conf
	// package (pulled in via main/json) is also what registers the `tun`
	// inbound handler.
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	mu       sync.Mutex
	instance *core.Instance
	probe    *core.Instance
	counters stats.Manager
)

// Version returns the embedded Xray-core version, e.g. "26.3.27".
func Version() string {
	return core.Version()
}

// IsRunning reports whether a core instance is currently up.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return instance != nil
}

// Start boots Xray-core from a JSON configuration.
//
//   - configJSON is a full Xray config; it must contain a `tun` inbound.
//   - tunFd is the utun descriptor owned by NEPacketTunnelProvider. Xray reads
//     and writes raw IP packets on it and never closes it.
//   - assetDir is where geoip.dat / geosite.dat live (may be empty).
//   - maxMemoryMB caps the Go heap. NetworkExtension processes are killed hard
//     when they exceed their (small) memory budget, so we make the collector
//     aggressive instead of letting the jetsam killer do it for us.
func Start(configJSON string, tunFd int, assetDir string, maxMemoryMB int) error {
	mu.Lock()
	defer mu.Unlock()

	if instance != nil {
		return errors.New("xray is already running")
	}
	if tunFd <= 0 {
		return errors.New("invalid tun file descriptor")
	}

	if maxMemoryMB > 0 {
		limit := int64(maxMemoryMB) << 20
		debug.SetGCPercent(10)
		debug.SetMemoryLimit(limit)
	}

	if err := os.Setenv(platform.TunFdKey, strconv.Itoa(tunFd)); err != nil {
		return err
	}
	if assetDir != "" {
		if err := os.Setenv(platform.AssetLocation, assetDir); err != nil {
			return err
		}
	}

	config, err := serial.LoadJSONConfig(bytes.NewReader([]byte(configJSON)))
	if err != nil {
		return err
	}

	inst, err := core.New(config)
	if err != nil {
		return err
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return err
	}

	instance = inst
	if manager, ok := inst.GetFeature(stats.ManagerType()).(stats.Manager); ok {
		counters = manager
	}
	return nil
}

// StartProbe boots a second, independent core instance for a health check of a
// server (its config uses a local SOCKS inbound). The main tunnel keeps
// running, so switching servers no longer drops live connections while the
// candidate is being tested.
func StartProbe(configJSON string, assetDir string) error {
	mu.Lock()
	defer mu.Unlock()

	if probe != nil {
		return errors.New("probe is already running")
	}
	if assetDir != "" {
		if err := os.Setenv(platform.AssetLocation, assetDir); err != nil {
			return err
		}
	}
	config, err := serial.LoadJSONConfig(bytes.NewReader([]byte(configJSON)))
	if err != nil {
		return err
	}
	inst, err := core.New(config)
	if err != nil {
		return err
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return err
	}
	probe = inst
	return nil
}

// StopProbe tears the probe instance down. It never touches the main tunnel.
func StopProbe() error {
	mu.Lock()
	defer mu.Unlock()

	if probe == nil {
		return nil
	}
	err := probe.Close()
	probe = nil
	debug.FreeOSMemory()
	return err
}

// ProbeRunning reports whether the probe instance is up.
func ProbeRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return probe != nil
}

// Stop tears the core down. The tun descriptor stays open — it belongs to
// NetworkExtension, which closes it when the tunnel goes away.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()

	if instance == nil {
		return nil
	}
	err := instance.Close()
	instance = nil
	counters = nil
	_ = os.Unsetenv(platform.TunFdKey)
	debug.FreeOSMemory()
	return err
}

// Uplink returns the bytes sent through the "proxy" outbound since start.
// Returns 0 when statistics are not enabled in the config.
func Uplink() int64 {
	return counter("outbound>>>proxy>>>traffic>>>uplink")
}

// Downlink returns the bytes received through the "proxy" outbound since start.
func Downlink() int64 {
	return counter("outbound>>>proxy>>>traffic>>>downlink")
}

// UplinkTotal returns the bytes sent through every outbound that carries user
// traffic. A profile with rules and groups has one outbound per server, so the
// single "proxy" counter no longer sees everything; direct, block and the DNS
// outbound are left out because they never reach a server.
func UplinkTotal() int64 {
	return sum("uplink")
}

// DownlinkTotal is UplinkTotal's counterpart for received bytes.
func DownlinkTotal() int64 {
	return sum("downlink")
}

var auxiliaryOutbounds = map[string]bool{"direct": true, "block": true, "dns-out": true}

type counterVisitor interface {
	VisitCounters(func(string, stats.Counter) bool)
}

func sum(direction string) int64 {
	mu.Lock()
	manager := counters
	mu.Unlock()
	visitor, ok := manager.(counterVisitor)
	if !ok {
		return 0
	}
	var total int64
	visitor.VisitCounters(func(name string, c stats.Counter) bool {
		// outbound>>>TAG>>>traffic>>>uplink
		parts := strings.Split(name, ">>>")
		if len(parts) == 4 && parts[0] == "outbound" && parts[3] == direction &&
			!auxiliaryOutbounds[parts[1]] {
			total += c.Value()
		}
		return true
	})
	return total
}

// Observation reports what the observatory last measured, as JSON:
// [{"tag": "...", "alive": true, "delay": 123}, ...]. Empty when the profile
// has no balancer to feed.
func Observation() string {
	mu.Lock()
	inst := instance
	mu.Unlock()
	if inst == nil {
		return "[]"
	}
	feature, ok := inst.GetFeature(extension.ObservatoryType()).(extension.Observatory)
	if !ok || feature == nil {
		return "[]"
	}
	message, err := feature.GetObservation(context.Background())
	if err != nil {
		return "[]"
	}
	result, ok := message.(*observatory.ObservationResult)
	if !ok {
		return "[]"
	}
	type entry struct {
		Tag   string `json:"tag"`
		Alive bool   `json:"alive"`
		Delay int64  `json:"delay"`
	}
	entries := make([]entry, 0, len(result.GetStatus()))
	for _, status := range result.GetStatus() {
		entries = append(entries, entry{Tag: status.GetOutboundTag(),
			Alive: status.GetAlive(), Delay: status.GetDelay()})
	}
	data, err := json.Marshal(entries)
	if err != nil {
		return "[]"
	}
	return string(data)
}

func counter(name string) int64 {
	mu.Lock()
	manager := counters
	mu.Unlock()
	if manager == nil {
		return 0
	}
	c := manager.GetCounter(name)
	if c == nil {
		return 0
	}
	return c.Value()
}

// CheckConfig validates a configuration without starting anything. Used by the
// app to surface bad server entries before the tunnel is brought up.
func CheckConfig(configJSON string) error {
	_, err := serial.LoadJSONConfig(bytes.NewReader([]byte(configJSON)))
	return err
}

// PrepareLog truncates the core log file so each session starts clean, and
// returns the path it was given. Xray itself appends to this file through the
// `log.error` config field.
func PrepareLog(path string) error {
	if path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	return f.Close()
}
