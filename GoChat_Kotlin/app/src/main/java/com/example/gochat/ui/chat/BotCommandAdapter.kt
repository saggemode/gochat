package com.example.gochat.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.data.model.BotCommand
import com.example.gochat.databinding.ItemBotCommandSuggestionBinding

class BotCommandAdapter(
    private val onItemClicked: (BotCommand) -> Unit
) : ListAdapter<BotCommand, BotCommandAdapter.CommandViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommandViewHolder {
        val binding = ItemBotCommandSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CommandViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommandViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CommandViewHolder(private val binding: ItemBotCommandSuggestionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(command: BotCommand) {
            binding.tvCommandSyntax.text = command.syntax
            binding.tvCommandDescription.text = command.description
            binding.tvCommandExample.text = command.example

            binding.root.setOnClickListener {
                onItemClicked(command)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<BotCommand>() {
        override fun areItemsTheSame(oldItem: BotCommand, newItem: BotCommand): Boolean {
            return oldItem.command == newItem.command
        }

        override fun areContentsTheSame(oldItem: BotCommand, newItem: BotCommand): Boolean {
            return oldItem == newItem
        }
    }
}
