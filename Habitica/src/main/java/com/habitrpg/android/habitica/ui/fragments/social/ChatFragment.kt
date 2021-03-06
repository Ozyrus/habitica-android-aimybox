package com.habitrpg.android.habitica.ui.fragments.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.habitrpg.android.habitica.MainNavDirections
import com.habitrpg.android.habitica.R
import com.habitrpg.android.habitica.components.UserComponent
import com.habitrpg.android.habitica.helpers.AppConfigManager
import com.habitrpg.android.habitica.helpers.MainNavigationController
import com.habitrpg.android.habitica.helpers.RxErrorHandler
import com.habitrpg.android.habitica.models.social.ChatMessage
import com.habitrpg.android.habitica.ui.activities.FullProfileActivity
import com.habitrpg.android.habitica.ui.activities.MainActivity
import com.habitrpg.android.habitica.ui.adapter.social.ChatRecyclerViewAdapter
import com.habitrpg.android.habitica.ui.fragments.BaseFragment
import com.habitrpg.android.habitica.ui.helpers.SafeDefaultItemAnimator
import com.habitrpg.android.habitica.ui.viewmodels.GroupViewModel
import com.habitrpg.android.habitica.ui.views.HabiticaSnackbar.Companion.showSnackbar
import com.habitrpg.android.habitica.ui.views.HabiticaSnackbar.SnackbarDisplayType
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.functions.Consumer
import io.realm.RealmResults
import kotlinx.android.synthetic.main.fragment_chat.*
import kotlinx.android.synthetic.main.tavern_chat_new_entry_item.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ChatFragment : BaseFragment() {

    var viewModel: GroupViewModel? = null

    @Inject
    lateinit var configManager: AppConfigManager

    private var chatAdapter: ChatRecyclerViewAdapter? = null
    private var navigatedOnceToFragment = false
    private var isScrolledToBottom = true
    private var refreshDisposable: Disposable? = null
    var autocompleteContext: String = ""

    override fun injectFragment(component: UserComponent) {
        component.inject(this)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val layoutManager = LinearLayoutManager(context)
        layoutManager.reverseLayout = true
        layoutManager.stackFromEnd = false
        recyclerView.layoutManager = layoutManager

        chatAdapter = ChatRecyclerViewAdapter(null, true, null, true)
        chatAdapter?.let {adapter ->
            compositeSubscription.add(adapter.getUserLabelClickFlowable().subscribe(Consumer { userId ->
                FullProfileActivity.open(userId)
            }, RxErrorHandler.handleEmptyError()))
            compositeSubscription.add(adapter.getDeleteMessageFlowable().subscribe(Consumer { this.showDeleteConfirmationDialog(it) }, RxErrorHandler.handleEmptyError()))
            compositeSubscription.add(adapter.getFlagMessageClickFlowable().subscribe(Consumer { this.showFlagConfirmationDialog(it) }, RxErrorHandler.handleEmptyError()))
            compositeSubscription.add(adapter.getReplyMessageEvents().subscribe(Consumer{ setReplyTo(it) }, RxErrorHandler.handleEmptyError()))
            compositeSubscription.add(adapter.getCopyMessageFlowable().subscribe(Consumer { this.copyMessageToClipboard(it) }, RxErrorHandler.handleEmptyError()))
            compositeSubscription.add(adapter.getLikeMessageFlowable().subscribe(Consumer { viewModel?.likeMessage(it) }, RxErrorHandler.handleEmptyError()))
        }

        chatBarView.sendAction = { sendChatMessage(it) }
        chatBarView.maxChatLength = configManager.maxChatLength()
        chatBarView.autocompleteContext = "party"
        chatBarView.groupID = viewModel?.getGroupData()?.value?.id

        recyclerView.adapter = chatAdapter
        recyclerView.itemAnimator = SafeDefaultItemAnimator()

        recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                isScrolledToBottom = layoutManager.findFirstVisibleItemPosition() == 0
            }
        })

        viewModel?.getChatMessages()?.subscribe(Consumer<RealmResults<ChatMessage>> { this.setChatMessages(it) }, RxErrorHandler.handleEmptyError())?.let { compositeSubscription.add(it) }

        communityGuidelinesReviewView.setOnClickListener {
            MainNavigationController.navigate(R.id.guidelinesActivity)
        }
        communityGuidelinesAcceptButton.setOnClickListener {
            viewModel?.updateUser("flags.communityGuidelinesAccepted", true)
        }

        viewModel?.getUserData()?.observe(viewLifecycleOwner, Observer {
            chatAdapter?.user = it
            if (it?.flags?.isCommunityGuidelinesAccepted == true) {
                communityGuidelinesView.visibility = View.GONE
                chatBarContent.visibility = View.VISIBLE
            } else {
                chatBarContent.visibility = View.GONE
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopAutoRefreshing()
    }

    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        super.setUserVisibleHint(isVisibleToUser)
        if (isVisibleToUser) {
            startAutoRefreshing()
        } else {
            stopAutoRefreshing()
        }
    }

    private fun startAutoRefreshing() {
        if (refreshDisposable != null && refreshDisposable?.isDisposed != true) {
            refreshDisposable?.dispose()
        }
        refreshDisposable = Observable.interval(30, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(Consumer {
            refresh()
        }, RxErrorHandler.handleEmptyError())
    }

    private fun stopAutoRefreshing() {
        if (refreshDisposable?.isDisposed != true) {
            refreshDisposable?.dispose()
            refreshDisposable = null
        }
    }

    private fun setReplyTo(username: String?) {
        val previousText = chatEditText.text.toString()
        if (previousText.contains("@$username")) {
            return
        }
        chatEditText.setText("@$username $previousText", TextView.BufferType.EDITABLE)
    }

    private fun refresh() {
        viewModel?.retrieveGroupChat {
            if (isScrolledToBottom && recyclerView != null) {
                recyclerView.scrollToPosition(0)
            }
        }
    }

    fun setNavigatedToFragment() {
        navigatedOnceToFragment = true
        markMessagesAsSeen()
    }

    private fun markMessagesAsSeen() {
        if (navigatedOnceToFragment) {
            viewModel?.markMessagesSeen()
        }
    }

    private fun copyMessageToClipboard(chatMessage: ChatMessage) {
        val clipMan = activity?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val messageText = ClipData.newPlainText("Chat message", chatMessage.text)
        clipMan?.setPrimaryClip(messageText)
        val activity = activity as? MainActivity
        if (activity != null) {
            showSnackbar(activity.snackbarContainer, getString(R.string.chat_message_copied), SnackbarDisplayType.NORMAL)
        }
    }

    private fun showFlagConfirmationDialog(chatMessage: ChatMessage) {
        val directions = MainNavDirections.actionGlobalReportMessageActivity(chatMessage.text ?: "", chatMessage.user ?: "", chatMessage.id)
        MainNavigationController.navigate(directions)
    }

    private fun showDeleteConfirmationDialog(chatMessage: ChatMessage) {
        val context = context
        if (context != null) {
            AlertDialog.Builder(context)
                    .setTitle(R.string.confirm_delete_tag_title)
                    .setMessage(R.string.confirm_delete_tag_message)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton(android.R.string.yes) { _, _ -> viewModel?.deleteMessage(chatMessage) }
                    .setNegativeButton(android.R.string.no, null).show()
        }
    }

    private fun setChatMessages(chatMessages: RealmResults<ChatMessage>) {
        chatAdapter?.updateData(chatMessages)
        viewModel?.socialRepository?.getUnmanagedCopy(chatMessages)?.let { chatBarView.chatMessages = it }

        viewModel?.gotNewMessages = true

        markMessagesAsSeen()
    }

    private fun sendChatMessage(chatText: String) {
        viewModel?.postGroupChat(chatText) {
            recyclerView?.scrollToPosition(0)
        }
    }
}
