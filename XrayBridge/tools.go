//go:build tools

package xray

// gomobile bind требует, чтобы зависимость x/mobile оставалась в go.mod.
import _ "golang.org/x/mobile/bind"
