package authz

import (
	"context"
	"fmt"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	authzpb "gochat/gen/authz"
)

// Client wraps the gRPC stub to provide a simpler interface for services to check permissions.
type Client struct {
	client authzpb.AuthzServiceClient
	conn   *grpc.ClientConn
}

// NewClient establishes a connection to the authorization service.
func NewClient(addr string) (*Client, error) {
	conn, err := grpc.Dial(addr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return nil, fmt.Errorf("dialing authz service at %s: %w", addr, err)
	}

	return &Client{
		client: authzpb.NewAuthzServiceClient(conn),
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

// Can checks if a user is authorized to perform an action on a resource.
func (c *Client) Can(ctx context.Context, userID, action, resourceID string, attributes map[string]string) (bool, string, error) {
	resp, err := c.client.Authorize(ctx, &authzpb.AuthorizeRequest{
		UserId:     userID,
		Action:     action,
		ResourceId: resourceID,
		Attributes: attributes,
	})
	if err != nil {
		return false, "", fmt.Errorf("authz gRPC call failed: %w", err)
	}
	return resp.Allowed, resp.Reason, nil
}
