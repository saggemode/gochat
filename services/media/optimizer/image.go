package optimizer

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/gif"
	"image/jpeg"
	"image/png"
	"io"
	"strings"

	"go.uber.org/zap"
)

const (
	MaxImageDimension = 2048 // Maximum pixel width/height for chat media
	JPEGQuality       = 82   // Visually lossless compression quality
)

// CompressImage decodes, downscales if necessary, and re-compresses an image stream.
func CompressImage(r io.Reader, mimeType string, log *zap.Logger) (io.Reader, string, int64, error) {
	// Decode image config first or full image
	img, format, err := image.Decode(r)
	if err != nil {
		return nil, "", 0, fmt.Errorf("decoding image: %w", err)
	}

	bounds := img.Bounds()
	width := bounds.Dx()
	height := bounds.Dy()

	targetWidth, targetHeight := calculateDimensions(width, height, MaxImageDimension)

	// If resizing is needed
	var finalImg image.Image = img
	if targetWidth != width || targetHeight != height {
		finalImg = resizeBilinear(img, targetWidth, targetHeight)
		log.Debug("downscaled image dimensions",
			zap.Int("orig_w", width), zap.Int("orig_h", height),
			zap.Int("new_w", targetWidth), zap.Int("new_h", targetHeight),
		)
	}

	buf := new(bytes.Buffer)
	outputMime := mimeType

	// Re-encode according to format / mime
	switch strings.ToLower(format) {
	case "png":
		// If PNG is very large, encode as JPEG for massive size savings, otherwise keep compressed PNG
		if targetWidth > 1200 || targetHeight > 1200 {
			err = jpeg.Encode(buf, finalImg, &jpeg.Options{Quality: JPEGQuality})
			outputMime = "image/jpeg"
		} else {
			encoder := png.Encoder{CompressionLevel: png.BestCompression}
			err = encoder.Encode(buf, finalImg)
		}
	case "gif":
		err = gif.Encode(buf, finalImg, nil)
	default: // jpeg / fallback
		err = jpeg.Encode(buf, finalImg, &jpeg.Options{Quality: JPEGQuality})
		outputMime = "image/jpeg"
	}

	if err != nil {
		return nil, "", 0, fmt.Errorf("encoding compressed image: %w", err)
	}

	return buf, outputMime, int64(buf.Len()), nil
}

// resizeBilinear implements fast pure-Go bilinear interpolation scaling.
func resizeBilinear(src image.Image, targetWidth, targetHeight int) *image.RGBA {
	dst := image.NewRGBA(image.Rect(0, 0, targetWidth, targetHeight))
	srcBounds := src.Bounds()
	srcW := srcBounds.Dx()
	srcH := srcBounds.Dy()

	if targetWidth == 0 || targetHeight == 0 || srcW == 0 || srcH == 0 {
		return dst
	}

	xRatio := float64(srcW-1) / float64(targetWidth)
	yRatio := float64(srcH-1) / float64(targetHeight)

	for y := 0; y < targetHeight; y++ {
		srcY := int(float64(y) * yRatio)
		yDiff := (float64(y) * yRatio) - float64(srcY)
		for x := 0; x < targetWidth; x++ {
			srcX := int(float64(x) * xRatio)
			xDiff := (float64(x) * xRatio) - float64(srcX)

			r1, g1, b1, a1 := src.At(srcBounds.Min.X+srcX, srcBounds.Min.Y+srcY).RGBA()
			r2, g2, b2, a2 := src.At(srcBounds.Min.X+srcX+1, srcBounds.Min.Y+srcY).RGBA()
			r3, g3, b3, a3 := src.At(srcBounds.Min.X+srcX, srcBounds.Min.Y+srcY+1).RGBA()
			r4, g4, b4, a4 := src.At(srcBounds.Min.X+srcX+1, srcBounds.Min.Y+srcY+1).RGBA()

			// Bilinear weighted interpolation
			r := uint8((float64(r1>>8)*(1-xDiff)*(1-yDiff) + float64(r2>>8)*(xDiff)*(1-yDiff) + float64(r3>>8)*(yDiff)*(1-xDiff) + float64(r4>>8)*(xDiff*yDiff)))
			g := uint8((float64(g1>>8)*(1-xDiff)*(1-yDiff) + float64(g2>>8)*(xDiff)*(1-yDiff) + float64(g3>>8)*(yDiff)*(1-xDiff) + float64(g4>>8)*(xDiff*yDiff)))
			b := uint8((float64(b1>>8)*(1-xDiff)*(1-yDiff) + float64(b2>>8)*(xDiff)*(1-yDiff) + float64(b3>>8)*(yDiff)*(1-xDiff) + float64(b4>>8)*(xDiff*yDiff)))
			a := uint8((float64(a1>>8)*(1-xDiff)*(1-yDiff) + float64(a2>>8)*(xDiff)*(1-yDiff) + float64(a3>>8)*(yDiff)*(1-xDiff) + float64(a4>>8)*(xDiff*yDiff)))

			dst.Set(x, y, color.RGBA{R: r, G: g, B: b, A: a})
		}
	}
	return dst
}

// calculateDimensions preserves aspect ratio within maxBound.
func calculateDimensions(w, h, maxBound int) (int, int) {
	if w <= maxBound && h <= maxBound {
		return w, h
	}

	if w > h {
		newW := maxBound
		newH := int(float64(h) * (float64(maxBound) / float64(w)))
		if newH < 1 {
			newH = 1
		}
		return newW, newH
	}

	newH := maxBound
	newW := int(float64(w) * (float64(maxBound) / float64(h)))
	if newW < 1 {
		newW = 1
	}
	return newW, newH
}
