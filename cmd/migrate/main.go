package main

import (
	"database/sql"
	"flag"
	"fmt"
	"log"

	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"

	"gochat/pkg/config"
)

func main() {
	flag.Parse()
	args := flag.Args()
	if len(args) < 1 {
		log.Fatal("Usage: migrate <up|down|status>")
	}
	cmd := args[0]

	cfg := config.Load()
	db, err := sql.Open("pgx", cfg.PostgresDSN)
	if err != nil {
		log.Fatalf("failed to open database: %v", err)
	}
	defer db.Close()

	if err := db.Ping(); err != nil {
		log.Fatalf("failed to ping database: %v", err)
	}

	if err := goose.SetDialect("postgres"); err != nil {
		log.Fatalf("failed to set goose dialect: %v", err)
	}

	dir := "./migrations"

	switch cmd {
	case "up":
		fmt.Println("Running migrations UP...")
		if err := goose.Up(db, dir); err != nil {
			log.Fatalf("goose up failed: %v", err)
		}
		fmt.Println("Migrations UP completed successfully.")
	case "down":
		fmt.Println("Running migrations DOWN...")
		if err := goose.Down(db, dir); err != nil {
			log.Fatalf("goose down failed: %v", err)
		}
		fmt.Println("Migrations DOWN completed successfully.")
	case "status":
		if err := goose.Status(db, dir); err != nil {
			log.Fatalf("goose status failed: %v", err)
		}
	default:
		log.Fatalf("unknown migration command: %q (expected up, down, or status)", cmd)
	}
}
