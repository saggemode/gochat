package chat

import (
	"context"
	"fmt"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	chatpb "gochat/gen/chat"
)

// Client wraps the gRPC stub to provide a simpler interface for services to interact with Chat Service.
type Client struct {
	client chatpb.ChatServiceClient
	conn   *grpc.ClientConn
}

// NewClient establishes a connection to the chat service.
func NewClient(addr string) (*Client, error) {
	conn, err := grpc.Dial(addr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return nil, fmt.Errorf("dialing chat service at %s: %w", addr, err)
	}

	return &Client{
		client: chatpb.NewChatServiceClient(conn),
		conn:   conn,
	}, nil
}

// Close closes the underlying gRPC connection.
func (c *Client) Close() error {
	if c.conn != nil {
		return c.conn.Close()
	}
	return nil
}

// SendMessage sends a message to a conversation.
func (c *Client) SendMessage(ctx context.Context, senderID, convID, content string) (*chatpb.Message, error) {
	resp, err := c.client.SendMessage(ctx, &chatpb.SendMessageRequest{
		SenderId:       senderID,
		ConversationId: convID,
		Content:        content,
		Type:           chatpb.MessageType_TEXT,
	})
	if err != nil {
		return nil, err
	}
	return resp.Message, nil
}
