package com.jarvis.assistant.ui.currency

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityCurrencyBinding
import com.jarvis.assistant.util.CurrencyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Home -> Currency card. Live crypto prices + fiat rates + converter.
 * Crypto auto-refreshes every 30 s while the screen is visible.
 */
class CurrencyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCurrencyBinding
    private val cryptoAdapter = RateAdapter()
    private val fiatAdapter = RateAdapter()
    private val prefs by lazy { getSharedPreferences("currency_prefs", Context.MODE_PRIVATE) }

    private var fromAsset = "USD"
    private var toAsset = "BDT"
    private var cryptoDisplay = "USD"
    private var fiatBase = "USD"
    private var section = 0 // 0 convert, 1 crypto, 2 fiat
    private var refreshJob: Job? = null
    private var lastManualRefresh = 0L
    private var attemptedLoad = false

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityCurrencyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        fromAsset = prefs.getString("from", "USD") ?: "USD"
        toAsset = prefs.getString("to", "BDT") ?: "BDT"
        cryptoDisplay = prefs.getString("crypto_display", "USD") ?: "USD"
        fiatBase = prefs.getString("fiat_base", "USD") ?: "USD"

        binding.rvCrypto.layoutManager = LinearLayoutManager(this)
        binding.rvCrypto.adapter = cryptoAdapter
        binding.rvFiat.layoutManager = LinearLayoutManager(this)
        binding.rvFiat.adapter = fiatAdapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastManualRefresh > 3000L) {
                lastManualRefresh = now
                lifecycleScope.launch { loadData(force = true) }
            }
        }

        binding.chipConvert.setOnClickListener { showSection(0) }
        binding.chipCrypto.setOnClickListener { showSection(1) }
        binding.chipFiat.setOnClickListener { showSection(2) }

        binding.etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { renderConvert() }
        })

        binding.tvFromAsset.setOnClickListener {
            showAssetPicker(CurrencyRepository.allAssets()) { picked ->
                fromAsset = picked
                prefs.edit().putString("from", picked).apply()
                renderAll()
            }
        }
        binding.tvToAsset.setOnClickListener {
            showAssetPicker(CurrencyRepository.allAssets()) { picked ->
                toAsset = picked
                prefs.edit().putString("to", picked).apply()
                renderAll()
            }
        }
        binding.btnSwap.setOnClickListener {
            val t = fromAsset
            fromAsset = toAsset
            toAsset = t
            prefs.edit().putString("from", fromAsset).putString("to", toAsset).apply()
            renderAll()
        }
        binding.tvCryptoDisplay.setOnClickListener {
            showAssetPicker(CurrencyRepository.allAssets()) { picked ->
                cryptoDisplay = picked
                prefs.edit().putString("crypto_display", picked).apply()
                renderAll()
            }
        }
        binding.tvFiatBase.setOnClickListener {
            val fiatOnly = CurrencyRepository.allAssets().filter { CurrencyRepository.isFiat(it) }
            showAssetPicker(fiatOnly) { picked ->
                fiatBase = picked
                prefs.edit().putString("fiat_base", picked).apply()
                renderAll()
            }
        }

        showSection(0)
        renderAll()
    }

    override fun onStart() {
        super.onStart()
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                loadData(force = false)
                delay(30_000L)
            }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        super.onStop()
    }

    private suspend fun loadData(force: Boolean) {
        if (CurrencyRepository.cachedCoins().isEmpty() && CurrencyRepository.cachedFiatRates().isEmpty()) {
            binding.tvStatus.text = getString(R.string.currency_loading)
        }
        CurrencyRepository.refreshAll(force)
        attemptedLoad = true
        renderAll()
    }

    private fun showSection(index: Int) {
        section = index
        binding.panelConvert.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.panelCrypto.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.panelFiat.visibility = if (index == 2) View.VISIBLE else View.GONE

        val accent = themeColor(R.attr.themeAccent, Color.parseColor("#2F6BFF"))
        val secondary = themeColor(R.attr.themeTextSecondary, Color.GRAY)
        val chips = listOf(binding.chipConvert, binding.chipCrypto, binding.chipFiat)
        for ((i, chip) in chips.withIndex()) {
            if (i == index) {
                chip.setBackgroundResource(R.drawable.bg_chip_selected)
                chip.setTextColor(accent)
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip_unselected)
                chip.setTextColor(secondary)
            }
        }
    }

    private fun renderAll() {
        binding.tvFromAsset.text = CurrencyRepository.assetLabel(fromAsset)
        binding.tvToAsset.text = CurrencyRepository.assetLabel(toAsset)
        binding.tvCryptoDisplay.text = CurrencyRepository.symbolOf(cryptoDisplay)
        binding.tvFiatBase.text = CurrencyRepository.symbolOf(fiatBase)
        renderStatus()
        renderConvert()
        renderCrypto()
        renderFiat()
    }

    private fun renderStatus() {
        val coinsAt = CurrencyRepository.cryptoUpdatedAt()
        val fiatAt = CurrencyRepository.fiatUpdatedAt()
        val newest = maxOf(coinsAt, fiatAt)
        if (newest == 0L) {
            // Nothing cached: either still loading the first time, or the request failed.
            binding.tvStatus.text = getString(
                if (attemptedLoad) R.string.currency_error else R.string.currency_loading
            )
            return
        }
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(newest))
        var text = getString(R.string.currency_updated, time)
        if (coinsAt > 0L && System.currentTimeMillis() - coinsAt > 120_000L) {
            text += " • " + getString(R.string.currency_rate_limited)
        }
        binding.tvStatus.text = text
    }

    private fun parseAmount(): Double? {
        val raw = binding.etAmount.text?.toString()?.trim().orEmpty().replace(',', '.')
        if (raw.isEmpty()) return null
        return raw.toDoubleOrNull()
    }

    private fun renderConvert() {
        val amount = parseAmount()
        val toSymbol = CurrencyRepository.symbolOf(toAsset)
        val fromSymbol = CurrencyRepository.symbolOf(fromAsset)
        val result = if (amount != null) CurrencyRepository.convert(amount, fromAsset, toAsset) else null
        binding.tvResult.text = if (result != null) {
            CurrencyRepository.formatNumber(result) + " " + toSymbol
        } else {
            "-"
        }
        val one = CurrencyRepository.convert(1.0, fromAsset, toAsset)
        binding.tvRateLine.text = if (one != null) {
            getString(R.string.currency_rate_line, fromSymbol, CurrencyRepository.formatNumber(one), toSymbol)
        } else {
            ""
        }
    }

    private fun renderCrypto() {
        val displaySymbol = CurrencyRepository.symbolOf(cryptoDisplay)
        val rows = CurrencyRepository.cachedCoins().map { coin ->
            val price = CurrencyRepository.convert(coin.priceUsd, "USD", cryptoDisplay)
            val change = coin.change24hPercent
            RateRow(
                title = coin.symbol,
                subtitle = coin.name,
                value = if (price != null) CurrencyRepository.formatNumber(price) + " " + displaySymbol else "-",
                change = if (change != null) String.format(Locale.getDefault(), "%+.2f%%", change) else null,
                changeSign = if (change == null) 0 else if (change > 0) 1 else if (change < 0) -1 else 0
            )
        }
        cryptoAdapter.submit(rows)
    }

    private fun renderFiat() {
        val rates = CurrencyRepository.cachedFiatRates()
        if (rates.isEmpty()) {
            fiatAdapter.submit(emptyList())
            return
        }
        val base = fiatBase.uppercase(Locale.ROOT)
        val ordered = CurrencyRepository.allAssets().filter { CurrencyRepository.isFiat(it) && it != base }
        val rows = ordered.mapNotNull { code ->
            val value = CurrencyRepository.convert(1.0, base, code) ?: return@mapNotNull null
            RateRow(
                title = code,
                subtitle = CurrencyRepository.fiatDisplayName(code),
                value = CurrencyRepository.formatNumber(value),
                change = null,
                changeSign = 0
            )
        }
        fiatAdapter.submit(rows)
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true) && tv.data != 0) tv.data else fallback
    }

    /** Searchable picker that follows the app theme colors. */
    private fun showAssetPicker(assets: List<String>, onPick: (String) -> Unit) {
        if (assets.isEmpty()) return
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val textPrimary = themeColor(R.attr.themeTextPrimary, Color.BLACK)
        val textMuted = themeColor(R.attr.themeTextMuted, Color.GRAY)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val search = EditText(this).apply {
            hint = getString(R.string.currency_search_hint)
            setSingleLine(true)
            setTextColor(textPrimary)
            setHintTextColor(textMuted)
        }
        val list = ListView(this)
        container.addView(
            search,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        container.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * density).toInt())
        )

        var shown: List<String> = assets
        val adapter = object : ArrayAdapter<String>(
            this@CurrencyActivity,
            android.R.layout.simple_list_item_1,
            assets.map { CurrencyRepository.assetLabel(it) }.toMutableList()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.setTextColor(textPrimary)
                return v
            }
        }
        list.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.currency_pick_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                shown = if (q.isEmpty()) assets else assets.filter {
                    CurrencyRepository.assetLabel(it).contains(q, ignoreCase = true)
                }
                adapter.clear()
                adapter.addAll(shown.map { CurrencyRepository.assetLabel(it) })
                adapter.notifyDataSetChanged()
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            if (position in shown.indices) {
                onPick(shown[position])
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
