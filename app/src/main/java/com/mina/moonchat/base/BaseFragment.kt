package com.mina.moonchat.base

import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController

abstract class BaseFragment: Fragment() {
    /**
     * Every fragment has to have an instance of a view model that extends from the BaseViewModel
     */
    abstract val _viewModel: BaseViewModel

    override fun onStart() {
        super.onStart()

        _viewModel.navigationCommand.observe(this) { command ->
            when (command) {
                is NavigationCommand.To -> findNavController().navigate(command.directions)
                is NavigationCommand.Back -> findNavController().popBackStack()
                is NavigationCommand.BackTo -> findNavController().popBackStack(
                    command.destinationId,
                    false
                )

                else -> {}
            }
        }

    }
}