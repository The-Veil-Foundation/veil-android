package qpack

type HeaderField struct {
	Name  string
	Value string
}

func (hf HeaderField) IsPseudo() bool {
	return len(hf.Name) != 0 && hf.Name[0] == ':'
}
