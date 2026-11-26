package qpack

import (
	"io"

	"golang.org/x/net/http2/hpack"
)

type Encoder struct {
	wrotePrefix bool

	w   io.Writer
	buf []byte
}

func NewEncoder(w io.Writer) *Encoder {
	return &Encoder{w: w}
}

func (e *Encoder) WriteField(f HeaderField) error {

	if !e.wrotePrefix {
		e.buf = appendVarInt(e.buf, 8, 0)
		e.buf = appendVarInt(e.buf, 7, 0)
		e.wrotePrefix = true
	}

	idxAndVals, nameFound := encoderMap[f.Name]
	if nameFound {
		if idxAndVals.values == nil {
			if len(f.Value) == 0 {
				e.writeIndexedField(idxAndVals.idx)
			} else {
				e.writeLiteralFieldWithNameReference(&f, idxAndVals.idx)
			}
		} else {
			valIdx, valueFound := idxAndVals.values[f.Value]
			if valueFound {
				e.writeIndexedField(valIdx)
			} else {
				e.writeLiteralFieldWithNameReference(&f, idxAndVals.idx)
			}
		}
	} else {
		e.writeLiteralFieldWithoutNameReference(f)
	}

	_, err := e.w.Write(e.buf)
	e.buf = e.buf[:0]
	return err
}

func (e *Encoder) Close() error {
	e.wrotePrefix = false
	return nil
}

func (e *Encoder) writeLiteralFieldWithoutNameReference(f HeaderField) {
	offset := len(e.buf)
	e.buf = appendVarInt(e.buf, 3, hpack.HuffmanEncodeLength(f.Name))
	e.buf[offset] ^= 0x20 ^ 0x8
	e.buf = hpack.AppendHuffmanString(e.buf, f.Name)
	offset = len(e.buf)
	e.buf = appendVarInt(e.buf, 7, hpack.HuffmanEncodeLength(f.Value))
	e.buf[offset] ^= 0x80
	e.buf = hpack.AppendHuffmanString(e.buf, f.Value)
}

func (e *Encoder) writeLiteralFieldWithNameReference(f *HeaderField, id uint8) {
	offset := len(e.buf)
	e.buf = appendVarInt(e.buf, 4, uint64(id))

	e.buf[offset] ^= 0x50
	offset = len(e.buf)
	e.buf = appendVarInt(e.buf, 7, hpack.HuffmanEncodeLength(f.Value))
	e.buf[offset] ^= 0x80
	e.buf = hpack.AppendHuffmanString(e.buf, f.Value)
}

func (e *Encoder) writeIndexedField(id uint8) {
	offset := len(e.buf)
	e.buf = appendVarInt(e.buf, 6, uint64(id))

	e.buf[offset] ^= 0xc0
}
