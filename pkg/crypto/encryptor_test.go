package crypto

import (
	"strings"
	"testing"
)

func TestEncryptDecrypt_Roundtrip(t *testing.T) {
	key, err := GenerateKey()
	if err != nil {
		t.Fatalf("GenerateKey() error = %v", err)
	}

	e, err := NewEncryptor(key)
	if err != nil {
		t.Fatalf("NewEncryptor() error = %v", err)
	}

	plaintext := "my-super-secret-prekey-data-123"
	encrypted, err := e.Encrypt(plaintext)
	if err != nil {
		t.Fatalf("Encrypt() error = %v", err)
	}

	if encrypted == plaintext {
		t.Error("ciphertext should differ from plaintext")
	}

	decrypted, err := e.Decrypt(encrypted)
	if err != nil {
		t.Fatalf("Decrypt() error = %v", err)
	}

	if decrypted != plaintext {
		t.Errorf("Decrypt() = %q, want %q", decrypted, plaintext)
	}
}

func TestEncrypt_EmptyPlaintext(t *testing.T) {
	key, _ := GenerateKey()
	e, _ := NewEncryptor(key)

	encrypted, err := e.Encrypt("")
	if err != nil {
		t.Fatalf("Encrypt(empty) error = %v", err)
	}
	if encrypted != "" {
		t.Errorf("Encrypt(empty) = %q, want empty string", encrypted)
	}
}

func TestDecrypt_EmptyInput(t *testing.T) {
	key, _ := GenerateKey()
	e, _ := NewEncryptor(key)

	decrypted, err := e.Decrypt("")
	if err != nil {
		t.Fatalf("Decrypt(empty) error = %v", err)
	}
	if decrypted != "" {
		t.Errorf("Decrypt(empty) = %q, want empty string", decrypted)
	}
}

func TestNewEncryptor_EmptyKey(t *testing.T) {
	_, err := NewEncryptor("")
	if err != ErrEmptyKey {
		t.Errorf("NewEncryptor(empty) error = %v, want %v", err, ErrEmptyKey)
	}
}

func TestNewEncryptor_BadKey(t *testing.T) {
	_, err := NewEncryptor("short-key")
	if err != ErrInvalidKeySize {
		t.Errorf("NewEncryptor(short) error = %v, want %v", err, ErrInvalidKeySize)
	}
}

func TestNewEncryptor_WrongByteLength(t *testing.T) {
	short := "dG9vc2hvcnQ="
	_, err := NewEncryptor(short)
	if err != ErrInvalidKeySize {
		t.Errorf("NewEncryptor(decoded=too short) error = %v, want %v", err, ErrInvalidKeySize)
	}
}

func TestGenerateKey_ReturnsDistinctKeys(t *testing.T) {
	a, _ := GenerateKey()
	b, _ := GenerateKey()
	if a == b {
		t.Error("GenerateKey returned duplicates (extremely unlikely)")
	}
}

func TestEncrypt_AllSizes(t *testing.T) {
	key, _ := GenerateKey()
	e, _ := NewEncryptor(key)

	sizes := []int{0, 1, 15, 16, 17, 255, 256, 1000, 4096}
	for _, size := range sizes {
		plaintext := strings.Repeat("a", size)
		encrypted, err := e.Encrypt(plaintext)
		if err != nil {
			t.Errorf("Encrypt(size=%d) error = %v", size, err)
			continue
		}

		if size > 0 && encrypted == plaintext {
			t.Errorf("Encrypt(size=%d) returned plaintext unchanged", size)
		}

		decrypted, err := e.Decrypt(encrypted)
		if err != nil {
			t.Errorf("Decrypt(size=%d) error = %v", size, err)
			continue
		}

		if decrypted != plaintext {
			t.Errorf("Decrypt(size=%d) = %q, want %q", size, decrypted, plaintext)
		}
	}
}

func TestDecrypt_CorruptedCiphertext(t *testing.T) {
	key, _ := GenerateKey()
	e, _ := NewEncryptor(key)

	encrypted, _ := e.Encrypt("some-data")
	corrupted := encrypted[:len(encrypted)-4] + "AAAA"

	_, err := e.Decrypt(corrupted)
	if err == nil {
		t.Error("Decrypt(corrupted) should return error")
	}
}

func TestGenerateKey_IsValid(t *testing.T) {
	key, err := GenerateKey()
	if err != nil {
		t.Fatalf("GenerateKey() error = %v", err)
	}

	e, err := NewEncryptor(key)
	if err != nil {
		t.Fatalf("NewEncryptor(valid key) error = %v", err)
	}

	_, err = e.Encrypt("test round-trip")
	if err != nil {
		t.Errorf("Encrypt(valid key) error = %v", err)
	}
}
