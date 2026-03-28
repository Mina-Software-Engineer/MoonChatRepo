package com.mina.moonchat.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mina.moonchat.adapters.ChatsAdapter
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentChatListBinding
import com.mina.moonchat.models.ChatItem
import com.mina.moonchat.viewmodels.ChatListViewModel
import com.mina.moonchat.viewmodels.ChatViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class ChatListFragment : BaseFragment() {
    private var _binding: FragmentChatListBinding? = null
    private val binding get() = _binding!!

    override val _viewModel: ChatListViewModel by inject()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentChatListBinding.inflate(inflater, container, false)

        val chatsRecyclerView = binding.chatRecyclerView
        val chatsRv = ChatsAdapter()

        chatsRecyclerView.apply {
            adapter = chatsRv
            layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
            setHasFixedSize(true)
        }


        lifecycleScope.launch {
            _viewModel.chatFlow.collectLatest { pagingData: PagingData<ChatItem> ->
                chatsRv.submitData(pagingData)
            }
        }
        return binding.root
    }
}