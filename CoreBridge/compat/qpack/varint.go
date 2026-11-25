package qpack

import (
	"errors"
	"io"
)

var errVarintOverflow = errors.New("varint integer overflow")

func appendVarInt(dst []byte, n byte, i uint64) []byte {
	k := uint64((1 << n) - 1)
	if i < k {
		return append(dst, byte(i))
	}
	dst = append(dst, byte(k))
	i -= k
	for ; i >= 128; i >>= 7 {
		dst = append(dst, byte(0x80|(i&0x7f)))
	}
	return append(dst, byte(i))
}

func readVarInt(n byte, p []byte) (i uint64, remain []byte, err error) {
	if n < 1 || n > 8 {
		panic("bad n")
	}
	if len(p) == 0 {
		return 0, p, io.ErrUnexpectedEOF
	}
	i = uint64(p[0])
	if n < 8 {
		i &= (1 << uint64(n)) - 1
	}
	if i < (1<<uint64(n))-1 {
		return i, p[1:], nil
	}

	origP := p
	p = p[1:]
	var m uint64
	for len(p) > 0 {
		b := p[0]
		p = p[1:]
		i += uint64(b&127) << m
		if b&128 == 0 {
			return i, p, nil
		}
		m += 7
		if m >= 63 {
			return 0, origP, errVarintOverflow
		}
	}
	return 0, origP, io.ErrUnexpectedEOF
}
