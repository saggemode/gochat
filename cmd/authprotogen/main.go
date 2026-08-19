// Command authprotogen regenerates gen/auth/auth.pb.go and
// gen/auth/auth_grpc.pb.go from the compiled proto/auth.proto descriptor
// WITHOUT requiring the protoc binary.
//
// It loads the existing compiled FileDescriptorProto from gochat/gen/auth,
// applies an in-memory patch that adds the multi-device session messages and
// RPCs, then feeds a CodeGeneratorRequest through the installed
// protoc-gen-go / protoc-gen-go-grpc plugins.
//
// Usage:
//
//	go run ./cmd/authprotogen
//
// NOTE: proto/auth.proto must be kept in sync manually with the additions
// below so that environments WITH protoc can regenerate normally
// (make proto).
package main

import (
	"fmt"
	"os"
	"os/exec"
	"strings"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/reflect/protodesc"
	"google.golang.org/protobuf/types/descriptorpb"
	"google.golang.org/protobuf/types/pluginpb"

	authpb "gochat/gen/auth"
)

func main() {
	fd := protodesc.ToFileDescriptorProto(authpb.File_proto_auth_proto)
	applyDeviceSessions(fd)

	req := &pluginpb.CodeGeneratorRequest{
		FileToGenerate: []string{"proto/auth.proto"},
		Parameter:      proto.String("module=gochat"),
		ProtoFile:      []*descriptorpb.FileDescriptorProto{fd},
		CompilerVersion: &pluginpb.Version{
			Major: proto.Int32(6),
			Minor: proto.Int32(31),
			Patch: proto.Int32(1),
		},
	}

	out, err := proto.Marshal(req)
	if err != nil {
		fatalf("marshal request: %v", err)
	}

	runPlugin("protoc-gen-go", out, "gen/auth/auth_grpc.pb.go.hold", "gen/auth/auth.pb.go")
	runPlugin("protoc-gen-go-grpc", out, "gen/auth/auth.pb.go.hold", "gen/auth/auth_grpc.pb.go")

	fmt.Println("regenerated gen/auth/auth.pb.go and gen/auth/auth_grpc.pb.go")
}

// runPlugin pipes a marshaled CodeGeneratorRequest into the plugin binary and
// writes the produced file content to dst.
func runPlugin(bin string, req []byte, tmpMarker, dst string) {
	cmd := exec.Command(bin)
	cmd.Stdin = strings.NewReader(string(req))
	var stdout, stderr strings.Builder
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		if stderr.Len() > 0 {
			defer os.Remove(tmpMarker)
		}
		fatalf("run %s: %v: %s", bin, err, stderr.String())
	}

	resp := &pluginpb.CodeGeneratorResponse{}
	if err := proto.Unmarshal([]byte(stdout.String()), resp); err != nil {
		fatalf("parse %s response: %v", bin, err)
	}
	if resp.Error != nil && *resp.Error != "" {
		fatalf("%s reported error: %s", bin, *resp.Error)
	}

	var file *pluginpb.CodeGeneratorResponse_File
	for _, f := range resp.File {
		if strings.HasSuffix(f.GetName(), ".go") {
			file = f
			break
		}
	}
	if file == nil {
		fatalf("%s returned no Go file", bin)
	}

	if err := os.WriteFile(dst, []byte(file.GetContent()), 0o644); err != nil {
		fatalf("write %s: %v", dst, err)
	}
}

// ── descriptor patch ─────────────────────────────────────────────────────────

type fieldSpec struct {
	name     string
	number   int32
	typ      descriptorpb.FieldDescriptorProto_Type
	label    descriptorpb.FieldDescriptorProto_Label
	typeName string // for message fields
	jsonName string
}

func stringField(name string, number int32) fieldSpec {
	return fieldSpec{name: name, number: number, typ: descriptorpb.FieldDescriptorProto_TYPE_STRING, label: descriptorpb.FieldDescriptorProto_LABEL_OPTIONAL, jsonName: camel(name)}
}

func boolField(name string, number int32) fieldSpec {
	return fieldSpec{name: name, number: number, typ: descriptorpb.FieldDescriptorProto_TYPE_BOOL, label: descriptorpb.FieldDescriptorProto_LABEL_OPTIONAL, jsonName: camel(name)}
}

func int64Field(name string, number int32) fieldSpec {
	return fieldSpec{name: name, number: number, typ: descriptorpb.FieldDescriptorProto_TYPE_INT64, label: descriptorpb.FieldDescriptorProto_LABEL_OPTIONAL, jsonName: camel(name)}
}

func messageField(name string, number int32, typeName string, repeated bool) fieldSpec {
	label := descriptorpb.FieldDescriptorProto_LABEL_OPTIONAL
	if repeated {
		label = descriptorpb.FieldDescriptorProto_LABEL_REPEATED
	}
	return fieldSpec{name: name, number: number, typ: descriptorpb.FieldDescriptorProto_TYPE_MESSAGE, label: label, typeName: typeName, jsonName: camel(name)}
}

func field(f fieldSpec) *descriptorpb.FieldDescriptorProto {
	out := &descriptorpb.FieldDescriptorProto{
		Name:     proto.String(f.name),
		Number:   proto.Int32(f.number),
		Label:    f.label.Enum(),
		Type:     f.typ.Enum(),
		JsonName: proto.String(f.jsonName),
	}
	if f.typeName != "" {
		out.TypeName = proto.String(f.typeName)
	}
	return out
}

func appendFields(m *descriptorpb.DescriptorProto, fields ...fieldSpec) {
	for _, f := range fields {
		m.Field = append(m.Field, field(f))
	}
}

func newMessage(name string, fields ...fieldSpec) *descriptorpb.DescriptorProto {
	m := &descriptorpb.DescriptorProto{Name: proto.String(name)}
	appendFields(m, fields...)
	return m
}

func camel(s string) string {
	parts := strings.Split(s, "_")
	for i := 1; i < len(parts); i++ {
		if parts[i] == "" {
			continue
		}
		parts[i] = strings.ToUpper(parts[i][:1]) + parts[i][1:]
	}
	return strings.Join(parts, "")
}

func applyDeviceSessions(fd *descriptorpb.FileDescriptorProto) {
	// ── extend existing messages ──────────────────────────────────────────────
	type msgPatch struct {
		name   string
		fields []fieldSpec
	}
	messagePatches := []msgPatch{
		{
			// proto/auth.proto already declares these; the compiled descriptor
			// was stale. Kept here so regeneration stays canonical.
			name: "User",
			fields: []fieldSpec{
				stringField("country_code", 12),
			},
		},
		{
			name: "RegisterRequest",
			fields: []fieldSpec{
				stringField("country_code", 5),
				stringField("device_name", 6),
				stringField("device_type", 7),
				stringField("platform", 8),
				stringField("push_token", 9),
				stringField("fingerprint", 10),
			},
		},
		{
			name: "RegisterResponse",
			fields: []fieldSpec{
				stringField("device_id", 4),
				messageField("device", 5, ".auth.Device", false),
			},
		},
		{
			name: "LoginRequest",
			fields: []fieldSpec{
				stringField("device_name", 3),
				stringField("device_type", 4),
				stringField("platform", 5),
				stringField("push_token", 6),
				stringField("fingerprint", 7),
			},
		},
		{
			name: "LoginResponse",
			fields: []fieldSpec{
				stringField("device_id", 4),
				messageField("device", 5, ".auth.Device", false),
			},
		},
		{
			name: "ValidateTokenResponse",
			fields: []fieldSpec{
				stringField("device_id", 4),
				stringField("device_name", 5),
			},
		},
		{
			name: "LogoutRequest",
			fields: []fieldSpec{
				stringField("device_id", 2),
			},
		},
	}

	msgByName := make(map[string]*descriptorpb.DescriptorProto, len(fd.MessageType))
	for _, m := range fd.MessageType {
		msgByName[m.GetName()] = m
	}
	for _, p := range messagePatches {
		m, ok := msgByName[p.name]
		if !ok {
			fatalf("message %q not found in descriptor", p.name)
		}
		for _, f := range p.fields {
			for _, existing := range m.Field {
				if existing.GetNumber() == f.number {
					fatalf("field number %d already used in %s", f.number, p.name)
				}
			}
		}
		appendFields(m, p.fields...)
	}

	// ── new messages ───────────────────────────────────────────────────────────
	newMessages := []*descriptorpb.DescriptorProto{
		newMessage("Device",
			stringField("id", 1),
			stringField("user_id", 2),
			stringField("device_name", 3),
			stringField("device_type", 4),
			stringField("platform", 5),
			stringField("push_token", 6),
			boolField("is_primary", 7),
			boolField("is_active", 8),
			int64Field("last_active", 9),
			int64Field("created_at", 10),
		),
		newMessage("ListDevicesRequest",
			stringField("user_id", 1),
		),
		newMessage("ListDevicesResponse",
			messageField("devices", 1, ".auth.Device", true),
		),
		newMessage("RevokeDeviceRequest",
			stringField("user_id", 1),
			stringField("device_id", 2),
		),
		newMessage("RevokeDeviceResponse",
			boolField("success", 1),
		),
		newMessage("UpdateDeviceRequest",
			stringField("user_id", 1),
			stringField("device_id", 2),
			stringField("device_name", 3),
			stringField("push_token", 4),
			stringField("platform", 5),
		),
		newMessage("UpdateDeviceResponse",
			messageField("device", 1, ".auth.Device", false),
		),
	}
	fd.MessageType = append(fd.MessageType, newMessages...)

	// ── new RPCs ────────────────────────────────────────────────────────────────
	svc := findService(fd, "AuthService")
	appendMethods(svc,
		method("ListDevices", ".auth.ListDevicesRequest", ".auth.ListDevicesResponse"),
		method("RevokeDevice", ".auth.RevokeDeviceRequest", ".auth.RevokeDeviceResponse"),
		method("UpdateDevice", ".auth.UpdateDeviceRequest", ".auth.UpdateDeviceResponse"),
	)
}

func findService(fd *descriptorpb.FileDescriptorProto, name string) *descriptorpb.ServiceDescriptorProto {
	for _, s := range fd.Service {
		if s.GetName() == name {
			return s
		}
	}
	fatalf("service %q not found in descriptor", name)
	return nil
}

func method(name, input, output string) *descriptorpb.MethodDescriptorProto {
	return &descriptorpb.MethodDescriptorProto{
		Name:       proto.String(name),
		InputType:  proto.String(input),
		OutputType: proto.String(output),
	}
}

func appendMethods(svc *descriptorpb.ServiceDescriptorProto, methods ...*descriptorpb.MethodDescriptorProto) {
	svc.Method = append(svc.Method, methods...)
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "authprotogen: "+format+"\n", args...)
	os.Exit(1)
}
