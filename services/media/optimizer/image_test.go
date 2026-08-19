package optimizer

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"testing"

	"go.uber.org/zap"
)

func createTestImage(w, h int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for x := 0; x < w; x++ {
		for y := 0; y < h; y++ {
			img.Set(x, y, color.RGBA{R: uint8(x % 256), G: uint8(y % 256), B: 120, A: 255})
		}
	}
	buf := new(bytes.Buffer)
	_ = png.Encode(buf, img)
	return buf.Bytes()
}

func TestCompressImage_Downscale(t *testing.T) {
	log := zap.NewNop()

	// 3000x2000 oversized image
	rawBytes := createTestImage(3000, 2000)

	reader, mime, size, err := CompressImage(bytes.NewReader(rawBytes), "image/png", log)
	if err != nil {
		t.Fatalf("CompressImage failed: %v", err)
	}

	if size <= 0 {
		t.Fatalf("expected positive output size, got %d", size)
	}

	// Verify compressed image dimensions do not exceed MaxImageDimension
	decoded, format, err := image.Decode(reader)
	if err != nil {
		t.Fatalf("failed to decode compressed image: %v", err)
	}

	bounds := decoded.Bounds()
	if bounds.Dx() > MaxImageDimension || bounds.Dy() > MaxImageDimension {
		t.Errorf("expected max dimension <= %d, got %dx%d", MaxImageDimension, bounds.Dx(), bounds.Dy())
	}

	t.Logf("original size: %d, compressed size: %d, format: %s, mime: %s", len(rawBytes), size, format, mime)
}

func TestCalculateDimensions(t *testing.T) {
	tests := []struct {
		w, h, maxBound int
		expW, expH     int
	}{
		{1000, 800, 2048, 1000, 800},
		{4000, 2000, 2048, 2048, 1024},
		{2000, 4000, 2048, 1024, 2048},
		{3000, 3000, 2048, 2048, 2048},
	}

	for _, tt := range tests {
		gotW, gotH := calculateDimensions(tt.w, tt.h, tt.maxBound)
		if gotW != tt.expW || gotH != tt.expH {
			t.Errorf("calculateDimensions(%d, %d, %d) = (%d, %d), expected (%d, %d)",
				tt.w, tt.h, tt.maxBound, gotW, gotH, tt.expW, tt.expH)
		}
	}
}
