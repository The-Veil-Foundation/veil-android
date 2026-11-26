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

	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	mu       sync.Mutex
	instance *core.Instance
	probe    *core.Instance
	counters stats.Manager
)

func Version() string {
	return core.Version()
}

func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return instance != nil
}

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

func ProbeRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return probe != nil
}

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

func Uplink() int64 {
	return counter("outbound>>>proxy>>>traffic>>>uplink")
}

func Downlink() int64 {
	return counter("outbound>>>proxy>>>traffic>>>downlink")
}

func UplinkTotal() int64 {
	return sum("uplink")
}

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

		parts := strings.Split(name, ">>>")
		if len(parts) == 4 && parts[0] == "outbound" && parts[3] == direction &&
			!auxiliaryOutbounds[parts[1]] {
			total += c.Value()
		}
		return true
	})
	return total
}

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

func CheckConfig(configJSON string) error {
	_, err := serial.LoadJSONConfig(bytes.NewReader([]byte(configJSON)))
	return err
}

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
