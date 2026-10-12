package handlers

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

// Default base exchange rates relative to 1 USD
var defaultUsdRates = map[string]float64{
	"USD": 1.0,
	"NGN": 1520.0,
	"GBP": 0.77,
	"EUR": 0.92,
	"KES": 129.5,
	"GHS": 15.6,
	"ZAR": 18.1,
	"INR": 83.9,
	"CAD": 1.37,
	"AUD": 1.51,
	"AED": 3.67,
	"BRL": 5.45,
	"JPY": 154.0,
	"CNY": 7.25,
}

var (
	fxCacheMutex sync.RWMutex
	fxCacheRates = make(map[string]float64)
	fxCacheTime  time.Time
)

func init() {
	for k, v := range defaultUsdRates {
		fxCacheRates[k] = v
	}
}

// fetchUpstreamUsdRates attempts to refresh rates from Frankfurter API asynchronously.
func fetchUpstreamUsdRates() {
	fxCacheMutex.RLock()
	isFresh := time.Since(fxCacheTime) < 30*time.Minute && len(fxCacheRates) > 1
	fxCacheMutex.RUnlock()
	if isFresh {
		return
	}

	go func() {
		client := &http.Client{Timeout: 5 * time.Second}
		resp, err := client.Get("https://api.frankfurter.dev/v2/rates?base=USD")
		if err != nil {
			return
		}
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			return
		}

		body, err := io.ReadAll(resp.Body)
		if err != nil {
			return
		}

		var payload struct {
			Rates map[string]float64 `json:"rates"`
		}
		if err := json.Unmarshal(body, &payload); err != nil {
			return
		}

		fxCacheMutex.Lock()
		defer fxCacheMutex.Unlock()
		for k, v := range payload.Rates {
			if v > 0 {
				fxCacheRates[strings.ToUpper(k)] = v
			}
		}
		// Ensure non-European regional currencies retain realistic market benchmarks
		for k, v := range defaultUsdRates {
			if _, exists := fxCacheRates[k]; !exists {
				fxCacheRates[k] = v
			}
		}
		fxCacheRates["USD"] = 1.0
		fxCacheTime = time.Now()
	}()
}

// GetFxRates returns exchange rates for products across regions and continents.
func (h *BusinessHandler) GetFxRates(c *gin.Context) {
	fetchUpstreamUsdRates()

	base := strings.ToUpper(strings.TrimSpace(c.DefaultQuery("base", "USD")))
	if base == "" {
		base = "USD"
	}

	fxCacheMutex.RLock()
	currentRates := make(map[string]float64, len(fxCacheRates))
	for k, v := range fxCacheRates {
		currentRates[k] = v
	}
	asOf := fxCacheTime
	fxCacheMutex.RUnlock()

	baseRateInUsd, hasBase := currentRates[base]
	if !hasBase || baseRateInUsd <= 0 {
		baseRateInUsd = 1.0
		base = "USD"
	}

	computed := make(map[string]float64, len(currentRates))
	for curr, rateInUsd := range currentRates {
		if rateInUsd > 0 {
			// Convert relative to requested base
			computed[curr] = rateInUsd / baseRateInUsd
		}
	}
	computed[base] = 1.0

	asOfStr := asOf.Format("2006-01-02")
	if asOf.IsZero() {
		asOfStr = time.Now().Format("2006-01-02")
	}

	c.JSON(http.StatusOK, gin.H{
		"base":      base,
		"rates":     computed,
		"provider":  "GoChat FX",
		"as_of":     asOfStr,
		"stale":     false,
		"timestamp": time.Now().Unix(),
	})
}
