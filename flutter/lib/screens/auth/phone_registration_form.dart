import 'package:flutter/material.dart';
import 'country_data.dart';

class PhoneRegistrationForm extends StatelessWidget {
  final Map<String, String> selectedCountry;
  final TextEditingController phoneController;
  final bool showCountryPicker;
  final String countrySearch;
  final bool loading;
  final VoidCallback onToggleCountryPicker;
  final ValueChanged<String> onCountrySearchChanged;
  final ValueChanged<String> onCountrySelected;
  final ValueChanged<String> onPhoneChanged;
  final VoidCallback onSubmit;

  const PhoneRegistrationForm({
    super.key,
    required this.selectedCountry,
    required this.phoneController,
    required this.showCountryPicker,
    required this.countrySearch,
    required this.loading,
    required this.onToggleCountryPicker,
    required this.onCountrySearchChanged,
    required this.onCountrySelected,
    required this.onPhoneChanged,
    required this.onSubmit,
  });

  @override
  Widget build(BuildContext context) {
    final filteredCountries = CountryData.filter(countrySearch);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'PHONE NUMBER',
          style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w600,
            color: Color(0xFFA1A1AA),
            letterSpacing: 0.8,
          ),
        ),
        const SizedBox(height: 8),

        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Country Code Selector Button
            GestureDetector(
              onTap: onToggleCountryPicker,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 13),
                decoration: BoxDecoration(
                  color: const Color(0xFF18181B).withValues(alpha: 0.7),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(
                    color: showCountryPicker ? const Color(0xFF10B981) : const Color(0xFF27272A),
                  ),
                ),
                child: Row(
                  children: [
                    Text(selectedCountry['flag']!, style: const TextStyle(fontSize: 16)),
                    const SizedBox(width: 6),
                    Text(
                      selectedCountry['dial']!,
                      style: const TextStyle(
                        color: Color(0xFFD4D4D8),
                        fontFamily: 'monospace',
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(width: 4),
                    const Icon(Icons.keyboard_arrow_down_rounded, color: Color(0xFF71717A), size: 16),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 8),

            // Phone Number Input
            Expanded(
              child: TextField(
                controller: phoneController,
                keyboardType: TextInputType.phone,
                style: const TextStyle(color: Colors.white, fontSize: 14.5),
                decoration: InputDecoration(
                  hintText: 'Phone number',
                  hintStyle: const TextStyle(color: Color(0xFF71717A), fontSize: 13.5),
                  filled: true,
                  fillColor: const Color(0xFF18181B).withValues(alpha: 0.7),
                  contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: const BorderSide(color: Color(0xFF27272A)),
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: const BorderSide(color: Color(0xFF10B981)),
                  ),
                ),
                onChanged: onPhoneChanged,
              ),
            ),
          ],
        ),

        // Country Search Dropdown
        if (showCountryPicker) ...[
          const SizedBox(height: 8),
          Container(
            constraints: const BoxConstraints(maxHeight: 220),
            decoration: BoxDecoration(
              color: const Color(0xFF18181B),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: const Color(0xFF27272A)),
              boxShadow: const [
                BoxShadow(color: Colors.black87, blurRadius: 20, offset: Offset(0, 8)),
              ],
            ),
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.all(8),
                  child: TextField(
                    style: const TextStyle(color: Colors.white, fontSize: 12.5),
                    decoration: InputDecoration(
                      hintText: 'Search country or code...',
                      hintStyle: const TextStyle(color: Color(0xFF71717A), fontSize: 12),
                      prefixIcon: const Icon(Icons.search, color: Color(0xFF71717A), size: 16),
                      filled: true,
                      fillColor: const Color(0xFF27272A),
                      contentPadding: const EdgeInsets.symmetric(vertical: 6),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(8),
                        borderSide: BorderSide.none,
                      ),
                    ),
                    onChanged: onCountrySearchChanged,
                  ),
                ),
                Expanded(
                  child: ListView.builder(
                    itemCount: filteredCountries.length,
                    itemBuilder: (context, idx) {
                      final c = filteredCountries[idx];
                      final isSelected = c['code'] == selectedCountry['code'];

                      return InkWell(
                        onTap: () => onCountrySelected(c['code']!),
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                          color: isSelected
                              ? const Color(0xFF10B981).withValues(alpha: 0.12)
                              : Colors.transparent,
                          child: Row(
                            children: [
                              Text(c['flag']!, style: const TextStyle(fontSize: 16)),
                              const SizedBox(width: 10),
                              Expanded(
                                child: Text(
                                  c['name']!,
                                  style: TextStyle(
                                    color: isSelected ? const Color(0xFF34D399) : Colors.white,
                                    fontSize: 13,
                                  ),
                                ),
                              ),
                              Text(
                                c['dial']!,
                                style: const TextStyle(
                                  color: Color(0xFF71717A),
                                  fontFamily: 'monospace',
                                  fontSize: 12,
                                ),
                              ),
                            ],
                          ),
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        ],

        const SizedBox(height: 8),
        const Text(
          'Fast & secure phone sign-up. Username & email can be added anytime in Settings.',
          style: TextStyle(fontSize: 11, color: Color(0xFF71717A), height: 1.3),
        ),
        const SizedBox(height: 20),

        // Submit Button
        SizedBox(
          width: double.infinity,
          height: 46,
          child: ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFF10B981),
              foregroundColor: Colors.black,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              elevation: 0,
            ),
            onPressed: loading ? null : onSubmit,
            child: loading
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.black),
                  )
                : const Text(
                    'Continue with Phone',
                    style: TextStyle(fontSize: 14.5, fontWeight: FontWeight.w700),
                  ),
          ),
        ),
      ],
    );
  }
}
