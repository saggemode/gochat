package repository

import (
	"context"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Role struct {
	Name        string
	Description string
}

type Permission struct {
	Name        string
	Description string
}

type Policy struct {
	ID          uuid.UUID
	Action      string
	Effect      string
	Condition   string
	Description string
}

type AuthzRepository struct {
	db *pgxpool.Pool
}

func NewAuthzRepository(db *pgxpool.Pool) *AuthzRepository {
	return &AuthzRepository{db: db}
}

func (r *AuthzRepository) AssignRole(ctx context.Context, userID uuid.UUID, roleName string) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO user_roles (user_id, role_name)
		VALUES ($1, $2)
		ON CONFLICT DO NOTHING
	`, userID, roleName)
	return err
}

func (r *AuthzRepository) RevokeRole(ctx context.Context, userID uuid.UUID, roleName string) error {
	_, err := r.db.Exec(ctx, `
		DELETE FROM user_roles
		WHERE user_id = $1 AND role_name = $2
	`, userID, roleName)
	return err
}

func (r *AuthzRepository) GetUserRoles(ctx context.Context, userID uuid.UUID) ([]string, error) {
	rows, err := r.db.Query(ctx, `
		SELECT role_name FROM user_roles WHERE user_id = $1
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var roles []string
	for rows.Next() {
		var role string
		if err := rows.Scan(&role); err != nil {
			return nil, err
		}
		roles = append(roles, role)
	}
	return roles, rows.Err()
}

func (r *AuthzRepository) GetUserPermissions(ctx context.Context, userID uuid.UUID) ([]string, error) {
	rows, err := r.db.Query(ctx, `
		SELECT DISTINCT rp.permission_name
		FROM user_roles ur
		INNER JOIN role_permissions rp ON rp.role_name = ur.role_name
		WHERE ur.user_id = $1
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var permissions []string
	for rows.Next() {
		var p string
		if err := rows.Scan(&p); err != nil {
			return nil, err
		}
		permissions = append(permissions, p)
	}
	return permissions, rows.Err()
}

func (r *AuthzRepository) CreatePolicy(ctx context.Context, action, effect, condition, description string) (*Policy, error) {
	p := &Policy{}
	err := r.db.QueryRow(ctx, `
		INSERT INTO abac_policies (action, effect, condition, description)
		VALUES ($1, $2, $3, $4)
		RETURNING id, action, effect, condition, description
	`, action, effect, condition, description).Scan(&p.ID, &p.Action, &p.Effect, &p.Condition, &p.Description)
	if err != nil {
		return nil, err
	}
	return p, nil
}

func (r *AuthzRepository) ListPolicies(ctx context.Context) ([]*Policy, error) {
	rows, err := r.db.Query(ctx, `
		SELECT id, action, effect, condition, description FROM abac_policies
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var policies []*Policy
	for rows.Next() {
		p := &Policy{}
		if err := rows.Scan(&p.ID, &p.Action, &p.Effect, &p.Condition, &p.Description); err != nil {
			return nil, err
		}
		policies = append(policies, p)
	}
	return policies, rows.Err()
}

// SeedPermissions allows the seeder to populate system roles/permissions relationships
func (r *AuthzRepository) SeedPermissions(ctx context.Context, rolePerms map[string][]string) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	for role, perms := range rolePerms {
		// Ensure role exists (default roles are seeded by migration, but we ensure here too)
		_, err = tx.Exec(ctx, `
			INSERT INTO roles (name, description)
			VALUES ($1, $2)
			ON CONFLICT (name) DO NOTHING
		`, role, role+" role")
		if err != nil {
			return err
		}

		for _, perm := range perms {
			// Ensure permission exists
			_, err = tx.Exec(ctx, `
				INSERT INTO permissions (name, description)
				VALUES ($1, $2)
				ON CONFLICT (name) DO NOTHING
			`, perm, perm+" permission")
			if err != nil {
				return err
			}

			// Map role to permission
			_, err = tx.Exec(ctx, `
				INSERT INTO role_permissions (role_name, permission_name)
				VALUES ($1, $2)
				ON CONFLICT DO NOTHING
			`, role, perm)
			if err != nil {
				return err
			}
		}
	}

	return tx.Commit(ctx)
}
