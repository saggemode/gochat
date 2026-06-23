package seeder

import (
	"context"

	"go.uber.org/zap"

	"gochat/services/authz/repository"
)

func Seed(ctx context.Context, repo *repository.AuthzRepository, log *zap.Logger) error {
	log.Info("seeding authorization permissions and roles...")

	// 1. Roles and their mapped permissions (RBAC)
	rolePerms := map[string][]string{
		"super_admin": {
			"*",
		},
		"admin": {
			"message:send", "message:edit_own", "message:edit_any", "message:delete_own", "message:delete_any",
			"message:pin", "conversation:create", "conversation:delete", "member:add", "member:remove", "member:promote",
			"media:upload", "media:delete_own", "media:delete_any", "user:ban",
		},
		"user": {
			"message:send", "message:edit_own", "message:delete_own", "message:pin", "conversation:create",
			"media:upload", "media:delete_own",
		},
	}

	err := repo.SeedPermissions(ctx, rolePerms)
	if err != nil {
		return err
	}

	// 2. Default ABAC Policies
	policies := []struct {
		Action      string
		Effect      string
		Condition   string
		Description string
	}{
		{
			Action:      "message:edit_own",
			Effect:      "allow",
			Condition:   "subject.id == resource.sender_id",
			Description: "Allow users to edit their own messages",
		},
		{
			Action:      "message:delete_own",
			Effect:      "allow",
			Condition:   "subject.id == resource.sender_id",
			Description: "Allow users to delete their own messages",
		},
		{
			Action:      "media:delete_own",
			Effect:      "allow",
			Condition:   "subject.id == resource.uploader_id",
			Description: "Allow users to delete their own uploaded media",
		},
		{
			Action:      "message:send",
			Effect:      "deny",
			Condition:   "subject.conversation_role == 'banned' || subject.system_role == 'banned'",
			Description: "Deny sending messages if user is banned",
		},
	}

	// Fetch existing policies to prevent duplicates
	existing, err := repo.ListPolicies(ctx)
	if err != nil {
		return err
	}

	for _, p := range policies {
		dup := false
		for _, ext := range existing {
			if ext.Action == p.Action && ext.Condition == p.Condition && ext.Effect == p.Effect {
				dup = true
				break
			}
		}
		if !dup {
			_, err = repo.CreatePolicy(ctx, p.Action, p.Effect, p.Condition, p.Description)
			if err != nil {
				return err
			}
		}
	}

	log.Info("authorization seeding completed successfully")
	return nil
}
