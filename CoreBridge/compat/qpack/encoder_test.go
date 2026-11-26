package qpack

import (
	"bytes"
	"io"
	"testing"

	"golang.org/x/net/http2/hpack"

	"github.com/stretchr/testify/require"
)

type errWriter struct {
	bytes.Buffer
	fail bool
}

func (ew *errWriter) Write(b []byte) (int, error) {
	if ew.fail {
		return 0, io.ErrClosedPipe
	}
	return ew.Buffer.Write(b)
}

func readPrefix(t *testing.T, data []byte) (rest []byte, requiredInsertCount uint64, deltaBase uint64) {
	var err error
	requiredInsertCount, rest, err = readVarInt(8, data)
	require.NoError(t, err)
	deltaBase, rest, err = readVarInt(7, rest)
	require.NoError(t, err)
	return
}

func checkHeaderField(t *testing.T, data []byte, hf HeaderField) []byte {
	require.Equal(t, uint8(0x20), data[0]&(0x80^0x40^0x20))
	require.NotZero(t, data[0]&0x8)
	nameLen, data, err := readVarInt(3, data)
	require.NoError(t, err)
	l := hpack.HuffmanEncodeLength(hf.Name)
	require.Equal(t, l, nameLen)
	decodedName, err := hpack.HuffmanDecodeToString(data[:l])
	require.NoError(t, err)
	require.Equal(t, hf.Name, decodedName)
	valueLen, data, err := readVarInt(7, data[l:])
	require.NoError(t, err)
	l = hpack.HuffmanEncodeLength(hf.Value)
	require.Equal(t, l, valueLen)
	decodedValue, err := hpack.HuffmanDecodeToString(data[:l])
	require.NoError(t, err)
	require.Equal(t, hf.Value, decodedValue)
	return data[l:]
}

func checkIndexedHeaderField(t *testing.T, data []byte, hf HeaderField) []byte {
	require.Equal(t, uint8(1), data[0]>>7)
	index, data, err := readVarInt(6, data)
	require.NoError(t, err)
	require.Equal(t, hf, staticTableEntries[index])
	return data
}

func checkHeaderFieldWithNameRef(t *testing.T, data []byte, hf HeaderField) []byte {

	require.Equal(t, uint8(1), data[0]>>6)
	index, data, err := readVarInt(4, data)
	require.NoError(t, err)
	require.Equal(t, hf.Name, staticTableEntries[index].Name)

	valueLen, data, err := readVarInt(7, data)
	require.NoError(t, err)
	l := hpack.HuffmanEncodeLength(hf.Value)
	require.Equal(t, l, valueLen)
	decodedValue, err := hpack.HuffmanDecodeToString(data[:l])
	require.NoError(t, err)
	require.Equal(t, hf.Value, decodedValue)
	return data[l:]
}

func TestEncoderEncodesSingleField(t *testing.T) {
	output := &errWriter{}
	encoder := NewEncoder(output)

	hf := HeaderField{Name: "foobar", Value: "lorem ipsum"}
	require.NoError(t, encoder.WriteField(hf))

	data, requiredInsertCount, deltaBase := readPrefix(t, output.Bytes())
	require.Zero(t, requiredInsertCount)
	require.Zero(t, deltaBase)

	data = checkHeaderField(t, data, hf)
	require.Empty(t, data)
}

func TestEncoderFailsToEncodeWhenWriterErrs(t *testing.T) {
	output := &errWriter{fail: true}
	encoder := NewEncoder(output)

	hf := HeaderField{Name: "foobar", Value: "lorem ipsum"}
	err := encoder.WriteField(hf)
	require.EqualError(t, err, "io: read/write on closed pipe")
}

func TestEncoderEncodesMultipleFields(t *testing.T) {
	output := &errWriter{}
	encoder := NewEncoder(output)

	hf1 := HeaderField{Name: "foobar", Value: "lorem ipsum"}
	hf2 := HeaderField{Name: "raboof", Value: "dolor sit amet"}
	require.NoError(t, encoder.WriteField(hf1))
	require.NoError(t, encoder.WriteField(hf2))

	data, requiredInsertCount, deltaBase := readPrefix(t, output.Bytes())
	require.Zero(t, requiredInsertCount)
	require.Zero(t, deltaBase)

	data = checkHeaderField(t, data, hf1)
	data = checkHeaderField(t, data, hf2)
	require.Empty(t, data)
}

func TestEncoderEncodesAllFieldsOfStaticTable(t *testing.T) {
	output := &errWriter{}
	encoder := NewEncoder(output)

	for _, hf := range staticTableEntries {
		require.NoError(t, encoder.WriteField(hf))
	}

	data, requiredInsertCount, deltaBase := readPrefix(t, output.Bytes())
	require.Zero(t, requiredInsertCount)
	require.Zero(t, deltaBase)

	for _, hf := range staticTableEntries {
		data = checkIndexedHeaderField(t, data, hf)
	}
	require.Empty(t, data)
}

func TestEncodeFieldsWithNameReferenceInStaticTable(t *testing.T) {
	output := &errWriter{}
	encoder := NewEncoder(output)

	hf1 := HeaderField{Name: ":status", Value: "666"}
	hf2 := HeaderField{Name: "server", Value: "lorem ipsum"}
	hf3 := HeaderField{Name: ":method", Value: ""}
	require.NoError(t, encoder.WriteField(hf1))
	require.NoError(t, encoder.WriteField(hf2))
	require.NoError(t, encoder.WriteField(hf3))

	data, requiredInsertCount, deltaBase := readPrefix(t, output.Bytes())
	require.Zero(t, requiredInsertCount)
	require.Zero(t, deltaBase)

	data = checkHeaderFieldWithNameRef(t, data, hf1)
	data = checkHeaderFieldWithNameRef(t, data, hf2)
	data = checkHeaderFieldWithNameRef(t, data, hf3)
	require.Empty(t, data)
}

func TestEncodeMultipleRequests(t *testing.T) {
	output := &errWriter{}
	encoder := NewEncoder(output)

	hf1 := HeaderField{Name: "foobar", Value: "lorem ipsum"}
	require.NoError(t, encoder.WriteField(hf1))
	data, requiredInsertCount, deltaBase := readPrefix(t, output.Bytes())
	require.Zero(t, requiredInsertCount)
	require.Zero(t, deltaBase)
	require.Empty(t, checkHeaderField(t, data, hf1))

	output.Reset()
	require.NoError(t, encoder.Close())
	hf2 := HeaderField{Name: "raboof", Value: "dolor sit amet"}
	require.NoError(t, encoder.WriteField(hf2))
	data, requiredInsertCount, deltaBase = readPrefix(t, output.Bytes())
	require.Zero(t, requiredInsertCount)
	require.Zero(t, deltaBase)
	require.Empty(t, checkHeaderField(t, data, hf2))
}
