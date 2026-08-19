package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"io"
)

var (
	ErrEmptyKey       = errors.New("encryption key is empty")
	ErrInvalidKeySize = errors.New("encryption key must be 32 bytes (base64-encoded)")
)

// Encryptor provides AES-256-GCM encryption-at-rest for sensitive data.
type Encryptor struct {
	key []byte
}

// NewEncryptor creates an Encryptor from a base64-encoded 32-byte key.
// Returns nil if the key is empty (no encryption — for dev only).
func NewEncryptor(key string) (*Encryptor, error) {
	if key == "" {
		return nil, ErrEmptyKey
	}

	decoded, err := base64.StdEncoding.DecodeString(key)
	if err != nil {
		return nil, ErrInvalidKeySize
	}

	if len(decoded) != 32 {
		return nil, ErrInvalidKeySize
	}

	return &Encryptor{key: decoded}, nil
}

// Encrypt encrypts plaintext using AES-256-GCM and returns a base64-encoded ciphertext.
func (e *Encryptor) Encrypt(plaintext string) (string, error) {
	if e == nil || len(e.key) == 0 {
		return plaintext, nil
	}
	if plaintext == "" {
		return plaintext, nil
	}

	block, err := aes.NewCipher(e.key)
	if err != nil {
		return "", err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}

	ciphertext := gcm.Seal(nonce, nonce, []byte(plaintext), nil)
	return base64.StdEncoding.EncodeToString(ciphertext), nil
}

// Decrypt decrypts a base64-encoded AES-256-GCM ciphertext.
func (e *Encryptor) Decrypt(encoded string) (string, error) {
	if e == nil || len(e.key) == 0 {
		return encoded, nil
	}
	if encoded == "" {
		return encoded, nil
	}

	data, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return "", err
	}

	block, err := aes.NewCipher(e.key)
	if err != nil {
		return "", err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}

	if len(data) < gcm.NonceSize() {
		return "", errors.New("ciphertext too short")
	}

	nonce := data[:gcm.NonceSize()]
	ciphertext := data[gcm.NonceSize():]

	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return "", err
	}

	return string(plaintext), nil
}

// GenerateKey generates a random 32-byte key and returns it base64-encoded.
func GenerateKey() (string, error) {
	key := make([]byte, 32)
	if _, err := io.ReadFull(rand.Reader, key); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(key), nil
}
