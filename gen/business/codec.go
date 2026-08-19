package business

import (
	"encoding/json"

	"google.golang.org/grpc/encoding"
)

func init() {
	encoding.RegisterCodec(JSONProtoCodec{})
}

type JSONProtoCodec struct{}

func (JSONProtoCodec) Name() string { return "json_proto" }

func (JSONProtoCodec) Marshal(v any) ([]byte, error) {
	return json.Marshal(v)
}

func (JSONProtoCodec) Unmarshal(data []byte, v any) error {
	return json.Unmarshal(data, v)
}
