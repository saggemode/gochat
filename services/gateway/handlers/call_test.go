package handlers

import "testing"

func TestSplitCSV(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want []string
	}{
		{"empty", "", nil},
		{"single", "turn:host:3478", []string{"turn:host:3478"}},
		{"multiple", "a,b,c", []string{"a", "b", "c"}},
		{"trims spaces", " a , b ,c ", []string{"a", "b", "c"}},
		{"drops blanks", "a,,b, ,c", []string{"a", "b", "c"}},
		{"only commas", ",,,", nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := splitCSV(tc.in)
			if len(got) != len(tc.want) {
				t.Fatalf("splitCSV(%q) = %v, want %v", tc.in, got, tc.want)
			}
			for i := range got {
				if got[i] != tc.want[i] {
					t.Fatalf("splitCSV(%q)[%d] = %q, want %q", tc.in, i, got[i], tc.want[i])
				}
			}
		})
	}
}
