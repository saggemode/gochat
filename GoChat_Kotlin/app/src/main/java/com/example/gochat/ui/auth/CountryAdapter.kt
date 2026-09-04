package com.example.gochat.ui.auth

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.databinding.ItemCountryBinding

class CountryAdapter(
    private var countries: List<Country>,
    private val onCountrySelected: (Country) -> Unit
) : RecyclerView.Adapter<CountryAdapter.CountryViewHolder>() {

    class CountryViewHolder(val binding: ItemCountryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CountryViewHolder {
        val binding = ItemCountryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CountryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CountryViewHolder, position: Int) {
        val country = countries[position]
        with(holder.binding) {
            tvCountryFlag.text = country.flag
            tvCountryName.text = country.name
            tvCountryDial.text = country.dial
            root.setOnClickListener { onCountrySelected(country) }
        }
    }

    override fun getItemCount(): Int = countries.size

    fun updateList(newList: List<Country>) {
        countries = newList
        notifyDataSetChanged()
    }
}
