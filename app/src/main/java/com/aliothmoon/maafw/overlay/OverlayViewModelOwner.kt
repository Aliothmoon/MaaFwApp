package com.aliothmoon.maafw.overlay

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import org.koin.core.component.KoinComponent

/**
 * 给悬浮窗里的 `ComposeView` 供三个 Owner
 *
 * 悬浮窗挂在 WindowManager 上、不属于任何 Activity，而 Compose 要求视图树上能找到
 * Lifecycle / ViewModelStore / SavedStateRegistry 三者，否则 `setContent` 直接抛
 *
 * 生命周期由 [start] / [stop] 手动推：面板显示时 RESUMED、隐藏时回 CREATED，
 * 这样 `collectAsStateWithLifecycle` 在隐藏期间不会空转
 */
class OverlayViewModelOwner : ViewModelStoreOwner,
    LifecycleOwner,
    SavedStateRegistryOwner,
    HasDefaultViewModelProviderFactory,
    KoinComponent {

    private val store = ViewModelStore()
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override val viewModelStore get() = store

    override val lifecycle get() = lifecycleRegistry

    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    /** 悬浮窗里取不到 Activity 的 VM，一律从 Koin 拿 */
    override val defaultViewModelProviderFactory by lazy {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = getKoin().get(modelClass.kotlin)
        }
    }

    override val defaultViewModelCreationExtras get() = MutableCreationExtras()

    fun start() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }
}
