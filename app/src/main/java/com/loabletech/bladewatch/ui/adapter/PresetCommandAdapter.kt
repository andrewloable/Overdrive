package com.overdrive.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.ui.model.PresetCommand
import com.google.android.material.chip.Chip
import com.overdrive.app.R

/**
 * Adapter for displaying preset command chips.
 */
class PresetCommandAdapter(
    private val presets: List<PresetCommand>,
    private val onClick: (PresetCommand) -> Unit
) : RecyclerView.Adapter<PresetCommandAdapter.PresetViewHolder>() {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetViewHolder {
        val chip = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_preset_command, parent, false) as Chip
        return PresetViewHolder(chip)
    }
    
    override fun onBindViewHolder(holder: PresetViewHolder, position: Int) {
        holder.bind(presets[position])
    }
    
    override fun getItemCount(): Int = presets.size
    
    inner class PresetViewHolder(private val chip: Chip) : RecyclerView.ViewHolder(chip) {
        fun bind(preset: PresetCommand) {
            chip.text = preset.label
            chip.setOnClickListener { onClick(preset) }
        }
    }
}
