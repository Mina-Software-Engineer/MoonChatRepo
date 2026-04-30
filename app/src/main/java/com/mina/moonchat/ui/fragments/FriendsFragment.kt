package com.mina.moonchat.ui.fragments
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.mina.moonchat.adapters.SearchFriendListAdapter
import com.mina.moonchat.adapters.SearchListener
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentFriendsBinding
import com.mina.moonchat.models.User
import com.mina.moonchat.viewmodels.FriendsViewModel
import org.koin.android.ext.android.inject

class FriendsFragment : BaseFragment() {
    private lateinit var _binding: FragmentFriendsBinding
    val binding get() = _binding
    override val _viewModel: FriendsViewModel by inject()

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    private lateinit var rvAdapter: SearchFriendListAdapter
    private var mList = ArrayList<User>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentFriendsBinding.inflate(inflater, container, false)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.search.apply {
            isActivated = true
            onActionViewExpanded()
            clearFocus()
            setIconifiedByDefault(true)
        }

        rvAdapter = SearchFriendListAdapter(SearchListener { user ->
            //Do navigation with user as parameter
            this.findNavController()
                .navigate(
                    MainScreenFragmentDirections
                        .actionMainScreenFragmentToChatFragment(user))
        })

        addDataToList()
        binding.friendsRecyclerView.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(requireActivity())
            this.adapter = rvAdapter
        }

    }

    private fun addDataToList() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrBlank()) {
                    mList.clear()
                    rvAdapter.submitList(emptyList())
                    return false
                }

                val query = db.collection("Users")
                    .orderBy("id")
                    .startAt(newText.trim())
                    .endAt(newText.trim() + "\uf8ff")

                showResultSearch(query)
                return false
            }
        })

    }

    private fun showResultSearch(query: Query) {
        mList.clear()
        query.get().addOnSuccessListener {
            it.documents.forEach { document ->
                document.toObject(User::class.java)?.let(mList::add)
            }
            rvAdapter.submitList(mList.toList())
        }
    }
}
