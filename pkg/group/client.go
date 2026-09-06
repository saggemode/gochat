package group

import (
	"context"
	"fmt"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	grouppb "gochat/gen/group"
)

// Client wraps the gRPC stub to provide a simpler interface for services to interact with Group Service.
type Client struct {
	client grouppb.GroupServiceClient
	conn   *grpc.ClientConn
}

// NewClient establishes a connection to the group service.
func NewClient(addr string) (*Client, error) {
	conn, err := grpc.Dial(addr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return nil, fmt.Errorf("dialing group service at %s: %w", addr, err)
	}

	return &Client{
		client: grouppb.NewGroupServiceClient(conn),
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

// GetBotConfig fetches the bot configuration for a specific group.
func (c *Client) GetBotConfig(ctx context.Context, groupID, requesterID string) (*grouppb.BotConfig, error) {
	resp, err := c.client.GetBotConfig(ctx, &grouppb.GetBotConfigRequest{
		GroupId:     groupID,
		RequesterId: requesterID,
	})
	if err != nil {
		return nil, err
	}
	return resp.Config, nil
}

func (c *Client) LogAuditAction(ctx context.Context, groupID, actorID, actionType, targetID, reason string) error {
	_, err := c.client.LogAuditAction(ctx, &grouppb.LogAuditActionRequest{
		ConversationId: groupID,
		ActorId:        actorID,
		ActionType:     actionType,
		TargetId:       targetID,
		Reason:         reason,
	})
	return err
}
