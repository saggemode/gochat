package main

import (
	"context"
	"encoding/json"

	"log"
	"net/http"
	"os"


	"github.com/jackc/pgx/v5/pgxpool"
)

type SearchResult struct {
	Type        string  `json:"type"` // "product", "chat", "contact"
	ID          string  `json:"id"`
	Title       string  `json:"title"`
	Description string  `json:"description"`
	ImageURL    string  `json:"image_url"`
	Score       float64 `json:"score"`
}

var db *pgxpool.Pool

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8095"
	}

	dsn := os.Getenv("POSTGRES_DSN")
	var err error
	db, err = pgxpool.New(context.Background(), dsn)
	if err != nil {
		log.Printf("Warning: Failed to connect to database: %v. Search will run in fallback mode.", err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/api/v1/search", handleGlobalSearch)
	mux.HandleFunc("/api/v1/search/suggestions", handleSearchSuggestions)

	log.Printf("🔍 Search Service listening on port %s", port)
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		log.Fatal(err)
	}
}

func handleGlobalSearch(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query().Get("q")
	if len(query) < 2 {
		json.NewEncoder(w).Encode([]SearchResult{})
		return
	}

	// In production, this would query Elasticsearch or Meilisearch.
	// For now, we use a robust Postgres query with ILIKE.
	results := []SearchResult{}

	if db != nil {
		rows, err := db.Query(r.Context(), `
			(SELECT 'product' as type, id::text, name as title, description, '' as img, 1.0 as score
			 FROM business.products
			 WHERE name ILIKE $1 OR description ILIKE $1 LIMIT 10)
			UNION ALL
			(SELECT 'contact' as type, id::text, display_name as title, status_text as description, avatar_url as img, 0.8 as score
			 FROM core.users
			 WHERE display_name ILIKE $1 OR phone ILIKE $1 LIMIT 5)
		`, "%"+query+"%")

		if err == nil {
			defer rows.Close()
			for rows.Next() {
				var res SearchResult
				var img *string
				if err := rows.Scan(&res.Type, &res.ID, &res.Title, &res.Description, &img, &res.Score); err == nil {
					if img != nil { res.ImageURL = *img }
					results = append(results, res)
				}
			}
		}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(results)
}

func handleSearchSuggestions(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query().Get("q")
	suggestions := []string{}

	if len(query) >= 1 && db != nil {
		rows, err := db.Query(r.Context(), `
			SELECT name FROM business.categories WHERE name ILIKE $1
			UNION
			SELECT name FROM business.products WHERE name ILIKE $1
			LIMIT 8
		`, query+"%")

		if err == nil {
			defer rows.Close()
			for rows.Next() {
				var s string
				if err := rows.Scan(&s); err == nil {
					suggestions = append(suggestions, s)
				}
			}
		}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(suggestions)
}
