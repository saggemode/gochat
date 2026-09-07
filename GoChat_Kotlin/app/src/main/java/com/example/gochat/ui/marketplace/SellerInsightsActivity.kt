package com.example.gochat.ui.marketplace

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gochat.R
import com.example.gochat.data.model.Product
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.ActivitySellerInsightsBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter

import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class SellerInsightsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySellerInsightsBinding

    @Inject
    lateinit var repository: MarketplaceRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySellerInsightsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadInsights()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadInsights() {
        lifecycleScope.launch {
            repository.getSellerInsights().onSuccess { insights ->
                binding.tvGrossRevenue.text = "$%.2f".format(insights.grossRevenue)
                binding.tvTotalOrders.text = insights.totalOrders.toString()
                binding.tvListedProducts.text = insights.listedProducts.toString()
                binding.tvTotalViews.text = insights.totalViews.toString()

                setupRevenueChart(insights.salesTrend)
                setupViewsChart(insights.viewsTrend)
                setupMostViewed(insights.mostViewedProducts)
            }.onFailure {
                // Handle error
            }
        }
    }

    private fun setupRevenueChart(trends: List<Pair<Long, Double>>) {

        if (trends.isEmpty()) {
            binding.revenueChart.setNoDataText("No sales data available yet.")
            return
        }

        val entries = trends.mapIndexed { index, pair ->
            Entry(index.toFloat(), pair.second.toFloat())
        }

        val dataSet = LineDataSet(entries, "Revenue").apply {
            color = Color.parseColor("#00A884")
            setCircleColor(Color.parseColor("#00A884"))
            lineWidth = 2f
            circleRadius = 4f
            setDrawCircleHole(false)
            valueTextColor = Color.WHITE
            setDrawFilled(true)
            fillColor = Color.parseColor("#00A884")
            fillAlpha = 50
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        val lineData = LineData(dataSet)
        binding.revenueChart.data = lineData

        val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
        val labels = trends.map { dateFormat.format(Date(it.first)) }

        binding.revenueChart.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            position = XAxis.XAxisPosition.BOTTOM
            textColor = Color.GRAY
            setDrawGridLines(false)
            granularity = 1f
        }

        binding.revenueChart.axisLeft.apply {
            textColor = Color.GRAY
            setDrawGridLines(true)
            gridColor = Color.parseColor("#1F2C33")
        }

        binding.revenueChart.axisRight.isEnabled = false
        binding.revenueChart.description.isEnabled = false
        binding.revenueChart.legend.isEnabled = false
        binding.revenueChart.invalidate()
    }

    private fun setupViewsChart(trends: List<Pair<Long, Int>>) {
        if (trends.isEmpty()) {
            binding.viewsChart.setNoDataText("No view data available.")
            return
        }

        val entries = trends.mapIndexed { index, pair ->
            BarEntry(index.toFloat(), pair.second.toFloat())
        }

        val dataSet = BarDataSet(entries, "Views").apply {
            color = Color.parseColor("#A855F7") // Purple
            valueTextColor = Color.WHITE
            setDrawValues(true)
        }

        val barData = BarData(dataSet)
        barData.barWidth = 0.6f
        binding.viewsChart.data = barData

        val dateFormat = SimpleDateFormat("EE", Locale.getDefault())
        val labels = trends.map { dateFormat.format(Date(it.first)) }

        binding.viewsChart.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            position = XAxis.XAxisPosition.BOTTOM
            textColor = Color.GRAY
            setDrawGridLines(false)
            granularity = 1f
        }

        binding.viewsChart.axisLeft.apply {
            textColor = Color.GRAY
            setDrawGridLines(true)
            gridColor = Color.parseColor("#1F2C33")
        }

        binding.viewsChart.axisRight.isEnabled = false
        binding.viewsChart.description.isEnabled = false
        binding.viewsChart.legend.isEnabled = false
        binding.viewsChart.invalidate()
    }


    private fun setupMostViewed(products: List<Product>) {
        val adapter = StoreProductAdapter(
            onClick = { /* Navigate to details */ },
            onEdit = { /* Navigate to edit */ },
            onDelete = { /* Confirm delete */ }
        )
        binding.rvMostViewed.layoutManager = LinearLayoutManager(this)
        binding.rvMostViewed.adapter = adapter
        adapter.submitList(products)
    }
}
