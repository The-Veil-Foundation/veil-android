package qpack

import (
	"bytes"
	"io"
	"reflect"
	"testing"
)

func TestLegacyAndCurrentDecoderCompatibility(t *testing.T) {
	want := []HeaderField{{Name: ":status", Value: "200"}, {Name: "x-veil", Value: "armv7"}}
	var buf bytes.Buffer
	enc := NewEncoder(&buf)
	for _, field := range want {
		if err := enc.WriteField(field); err != nil {
			t.Fatal(err)
		}
	}
	legacy := NewDecoder(func(HeaderField) { t.Fatal("DecodeFull must not invoke the legacy callback") })
	for range 2 {
		got, err := legacy.DecodeFull(buf.Bytes())
		if err != nil || !reflect.DeepEqual(got, want) {
			t.Fatalf("DecodeFull: %v, %v", got, err)
		}
	}
	next := NewDecoder().Decode(buf.Bytes())
	for _, field := range want {
		got, err := next()
		if err != nil || got != field {
			t.Fatalf("Decode: %v, %v", got, err)
		}
	}
	if _, err := next(); err != io.EOF {
		t.Fatalf("end of block: %v", err)
	}
	if _, err := legacy.DecodeFull([]byte{0xff}); err == nil {
		t.Fatal("accepted malformed block")
	}
}
