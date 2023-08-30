package org.wikipedia.diff

import android.graphics.Rect
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.wikipedia.Constants.InvokeSource
import org.wikipedia.R
import org.wikipedia.activity.FragmentUtil
import org.wikipedia.analytics.eventplatform.EditHistoryInteractionEvent
import org.wikipedia.auth.AccountUtil
import org.wikipedia.commons.FilePageActivity
import org.wikipedia.databinding.FragmentArticleEditDetailsBinding
import org.wikipedia.dataclient.mwapi.MwQueryPage.Revision
import org.wikipedia.dataclient.okhttp.HttpStatusException
import org.wikipedia.dataclient.watch.Watch
import org.wikipedia.history.HistoryEntry
import org.wikipedia.page.ExclusiveBottomSheetPresenter
import org.wikipedia.page.Namespace
import org.wikipedia.page.PageActivity
import org.wikipedia.page.PageTitle
import org.wikipedia.page.linkpreview.LinkPreviewDialog
import org.wikipedia.readinglist.AddToReadingListDialog
import org.wikipedia.staticdata.UserAliasData
import org.wikipedia.talk.TalkTopicsActivity
import org.wikipedia.talk.UserTalkPopupHelper
import org.wikipedia.util.*
import org.wikipedia.util.log.L
import org.wikipedia.watchlist.WatchlistExpiry
import org.wikipedia.watchlist.WatchlistExpiryDialog

class ArticleEditDetailsFragment : Fragment(), WatchlistExpiryDialog.Callback, LinkPreviewDialog.Callback, MenuProvider {
    interface Callback {
        fun onUndoSuccess();
        fun onRollbackSuccess();
    }

    private var _binding: FragmentArticleEditDetailsBinding? = null
    private val binding get() = _binding!!

    private var isWatched = false
    private var hasWatchlistExpiry = false

    private val viewModel: ArticleEditDetailsViewModel by viewModels { ArticleEditDetailsViewModel.Factory(requireArguments()) }
    private var editHistoryInteractionEvent: EditHistoryInteractionEvent? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        super.onCreateView(inflater, container, savedInstanceState)
        _binding = FragmentArticleEditDetailsBinding.inflate(inflater, container, false)
        binding.diffRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        FeedbackUtil.setButtonLongPressToast(binding.newerIdButton, binding.olderIdButton)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpListeners()
        setLoadingState()
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        if (!viewModel.fromRecentEdits) {
            (requireActivity() as AppCompatActivity).supportActionBar?.title = getString(R.string.revision_diff_compare)
            binding.articleTitleView.text = StringUtil.fromHtml(viewModel.pageTitle.displayText)
        }

        viewModel.watchedStatus.observe(viewLifecycleOwner) {
            if (it is Resource.Success) {
                if (editHistoryInteractionEvent == null) {
                    editHistoryInteractionEvent = EditHistoryInteractionEvent(viewModel.pageTitle.wikiSite.dbName(), viewModel.pageId)
                    editHistoryInteractionEvent?.logRevision()
                }
                isWatched = it.data.watched
                hasWatchlistExpiry = it.data.hasWatchlistExpiry()
                updateWatchButton(isWatched, hasWatchlistExpiry)
            } else if (it is Resource.Error) {
                setErrorState(it.throwable)
            }
            requireActivity().invalidateOptionsMenu()
        }

        viewModel.revisionDetails.observe(viewLifecycleOwner) {
            if (it is Resource.Success) {
                updateDiffCharCountView(viewModel.diffSize)
                updateAfterRevisionFetchSuccess()
            } else if (it is Resource.Error) {
                setErrorState(it.throwable)
            }
        }

        viewModel.singleRevisionText.observe(viewLifecycleOwner) {
            if (it is Resource.Success) {
                binding.diffRecyclerView.adapter = DiffUtil.DiffLinesAdapter(DiffUtil.buildDiffLinesList(requireContext(), it.data))
                updateAfterDiffFetchSuccess()
                binding.progressBar.isVisible = false
            } else if (it is Resource.Error) {
                setErrorState(it.throwable)
            }
        }

        viewModel.thankStatus.observe(viewLifecycleOwner) {
            if (it is Resource.Success) {
                FeedbackUtil.showMessage(requireActivity(), getString(R.string.thank_success_message,
                        viewModel.revisionTo?.user))
                binding.thankIcon.setImageResource(R.drawable.ic_heart_24)
                binding.thankButton.isEnabled = false
                editHistoryInteractionEvent?.logThankSuccess()
            } else if (it is Resource.Error) {
                setErrorState(it.throwable)
                editHistoryInteractionEvent?.logThankFail()
            }
        }

        viewModel.watchResponse.observe(viewLifecycleOwner) {
            if (it is Resource.Success) {
                val firstWatch = it.data.getFirst()
                if (firstWatch != null) {
                    showWatchlistSnackbar(viewModel.lastWatchExpiry, firstWatch)
                }
            } else if (it is Resource.Error) {
                setErrorState(it.throwable)
            }
            requireActivity().invalidateOptionsMenu()
        }

        viewModel.undoEditResponse.observe(viewLifecycleOwner) {
            binding.progressBar.isVisible = false
            if (it is Resource.Success) {
                setLoadingState()
                viewModel.getRevisionDetails(it.data.edit!!.newRevId)
                FeedbackUtil.makeSnackbar(requireActivity(), getString(R.string.revision_undo_success)).show()
                editHistoryInteractionEvent?.logUndoSuccess()
                callback()?.onUndoSuccess()
            } else if (it is Resource.Error) {
                it.throwable.printStackTrace()
                FeedbackUtil.showError(requireActivity(), it.throwable)
                editHistoryInteractionEvent?.logUndoFail()
            }
        }

        viewModel.rollbackRights.observe(viewLifecycleOwner) {
            binding.progressBar.isVisible = false
            if (it is Resource.Success) {
                updateActionButtons()
            } else if (it is Resource.Error) {
                it.throwable.printStackTrace()
                FeedbackUtil.showError(requireActivity(), it.throwable)
            }
        }

        viewModel.rollbackResponse.observe(viewLifecycleOwner) {
            binding.progressBar.isVisible = false
            if (it is Resource.Success) {
                setLoadingState()
                viewModel.getRevisionDetails(it.data.rollback?.revision ?: 0)
                FeedbackUtil.makeSnackbar(requireActivity(), getString(R.string.revision_rollback_success), FeedbackUtil.LENGTH_DEFAULT).show()
                callback()?.onRollbackSuccess()
            } else if (it is Resource.Error) {
                it.throwable.printStackTrace()
                FeedbackUtil.showError(requireActivity(), it.throwable)
            }
        }

        viewModel.diffText.observe(viewLifecycleOwner) {
            if (it is Resource.Success) {
                binding.diffRecyclerView.adapter = DiffUtil.DiffLinesAdapter(DiffUtil.buildDiffLinesList(requireContext(), it.data.diff))
                updateAfterDiffFetchSuccess()
                updateActionButtons()
                binding.progressBar.isVisible = false
            } else if (it is Resource.Error) {
                if (it.throwable is HttpStatusException && it.throwable.code == 403) {
                    binding.progressBar.isVisible = false
                    binding.diffRecyclerView.isVisible = false
                    binding.undoButton.isVisible = false
                    binding.thankButton.isVisible = false
                    binding.diffUnavailableContainer.isVisible = true
                } else {
                    setErrorState(it.throwable)
                }
            }
        }

        L10nUtil.setConditionalLayoutDirection(requireView(), viewModel.pageTitle.wikiSite.languageCode)

        binding.scrollContainer.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            val bounds = Rect()
            binding.contentContainer.offsetDescendantRectToMyCoords(binding.articleTitleDivider, bounds)
            binding.overlayRevisionDetailsView.isVisible = scrollY > bounds.top
        })
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun setUpListeners() {
        binding.articleTitleView.setOnClickListener {
            if (viewModel.pageTitle.namespace() == Namespace.USER_TALK || viewModel.pageTitle.namespace() == Namespace.TALK) {
                startActivity(TalkTopicsActivity.newIntent(requireContext(), viewModel.pageTitle, InvokeSource.DIFF_ACTIVITY))
            } else if (viewModel.pageTitle.namespace() == Namespace.FILE) {
                startActivity(FilePageActivity.newIntent(requireContext(), viewModel.pageTitle))
            } else {
                ExclusiveBottomSheetPresenter.show(childFragmentManager, LinkPreviewDialog.newInstance(
                        HistoryEntry(viewModel.pageTitle, HistoryEntry.SOURCE_EDIT_DIFF_DETAILS), null))
            }
        }
        binding.newerIdButton.setOnClickListener {
            setLoadingState()
            viewModel.goForward()
            editHistoryInteractionEvent?.logNewerEditChevronClick()
        }
        binding.olderIdButton.setOnClickListener {
            setLoadingState()
            viewModel.goBackward()
            editHistoryInteractionEvent?.logOlderEditChevronClick()
        }

        binding.usernameFromButton.setOnClickListener {
            showUserPopupMenu(viewModel.revisionFrom, binding.usernameFromButton)
        }

        binding.usernameToButton.setOnClickListener {
            showUserPopupMenu(viewModel.revisionTo, binding.usernameToButton)
        }

        binding.thankButton.setOnClickListener {
            showThankDialog()
            editHistoryInteractionEvent?.logThankTry()
        }

        binding.undoButton.setOnClickListener {
            val canUndo = viewModel.revisionFrom != null && AccountUtil.isLoggedIn
            val canRollback = AccountUtil.isLoggedIn && viewModel.hasRollbackRights && !viewModel.canGoForward

            if (canUndo && canRollback) {
                PopupMenu(requireContext(), binding.undoLabel, Gravity.END).apply {
                    menuInflater.inflate(R.menu.menu_context_undo, menu)
                    setForceShowIcon(true)
                    setOnMenuItemClickListener { menuItem ->
                        when (menuItem.itemId) {
                            R.id.menu_undo -> {
                                showUndoDialog()
                                editHistoryInteractionEvent?.logUndoTry()
                                true
                            }
                            R.id.menu_rollback -> {
                                showRollbackDialog()
                                true
                            }
                            else -> false
                        }
                    }
                    show()
                }
            } else if (canUndo) {
                showUndoDialog()
                editHistoryInteractionEvent?.logUndoTry()
            }
        }

        binding.watchButton.setOnClickListener {
            viewModel.watchOrUnwatch(isWatched, WatchlistExpiry.NEVER, isWatched)
            if (isWatched) editHistoryInteractionEvent?.logUnwatchClick() else editHistoryInteractionEvent?.logWatchClick()
        }
        updateWatchButton(isWatched, hasWatchlistExpiry)

        binding.errorView.backClickListener = View.OnClickListener { requireActivity().finish() }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_edit_details, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_share_edit -> {
                ShareUtil.shareText(requireContext(), StringUtil.fromHtml(viewModel.pageTitle.displayText).toString(), getSharableDiffUrl())
                editHistoryInteractionEvent?.logShareClick()
                true
            }
            R.id.menu_copy_link_to_clipboard -> {
                copyLink(getSharableDiffUrl())
                true
            }
            else -> false
        }
    }

    private fun showUserPopupMenu(revision: Revision?, anchorView: View) {
        revision?.let {
            UserTalkPopupHelper.show(requireActivity() as AppCompatActivity,
                    PageTitle(UserAliasData.valueFor(viewModel.pageTitle.wikiSite.languageCode),
                            it.user, viewModel.pageTitle.wikiSite), it.isAnon, anchorView,
                    InvokeSource.DIFF_ACTIVITY, HistoryEntry.SOURCE_EDIT_DIFF_DETAILS)
        }
    }

    private fun setErrorState(t: Throwable) {
        L.e(t)
        binding.errorView.setError(t)
        binding.errorView.isVisible = true
        binding.revisionDetailsView.isVisible = false
        binding.progressBar.isVisible = false
    }

    private fun updateDiffCharCountView(diffSize: Int) {
        binding.diffCharacterCountView.text = StringUtil.getDiffBytesText(requireContext(), diffSize)
        if (diffSize >= 0) {
            val diffColor = if (diffSize > 0) R.attr.success_color else R.attr.secondary_color
            binding.diffCharacterCountView.setTextColor(ResourceUtil.getThemedColor(requireContext(), diffColor))
        } else {
            binding.diffCharacterCountView.setTextColor(ResourceUtil.getThemedColor(requireContext(), R.attr.destructive_color))
        }
    }

    private fun setLoadingState() {
        binding.progressBar.isVisible = true
        binding.revisionDetailsView.isVisible = false
        binding.diffRecyclerView.isVisible = false
        binding.diffUnavailableContainer.isVisible = false
        binding.thankButton.isVisible = false
        binding.undoButton.isVisible = false
    }

    private fun updateAfterRevisionFetchSuccess() {
        binding.articleTitleView.text = StringUtil.fromHtml(viewModel.pageTitle.displayText)

        if (viewModel.revisionFrom != null) {
            binding.usernameFromButton.text = viewModel.revisionFrom!!.user
            binding.revisionFromTimestamp.text = DateUtil.getTimeAndDateString(requireContext(), viewModel.revisionFrom!!.timeStamp)
            binding.revisionFromEditComment.text = StringUtil.fromHtml(viewModel.revisionFrom!!.parsedcomment.trim())
            binding.revisionFromTimestamp.setTextColor(ResourceUtil.getThemedColor(requireContext(), R.attr.progressive_color))
            binding.overlayRevisionFromTimestamp.setTextColor(ResourceUtil.getThemedColor(requireContext(), R.attr.progressive_color))
            binding.usernameFromButton.isVisible = true
            binding.revisionFromEditComment.isVisible = true
        } else {
            binding.usernameFromButton.isVisible = false
            binding.revisionFromEditComment.isVisible = false
            binding.revisionFromTimestamp.setTextColor(ResourceUtil.getThemedColor(requireContext(), R.attr.placeholder_color))
            binding.overlayRevisionFromTimestamp.setTextColor(ResourceUtil.getThemedColor(requireContext(), R.attr.placeholder_color))
            binding.revisionFromTimestamp.text = getString(R.string.revision_initial_none)
        }
        binding.overlayRevisionFromTimestamp.text = binding.revisionFromTimestamp.text

        binding.oresDamagingButton.isVisible = false

        viewModel.revisionTo?.let {
            binding.usernameToButton.text = it.user
            binding.revisionToTimestamp.text = DateUtil.getTimeAndDateString(requireContext(), it.timeStamp)
            binding.overlayRevisionToTimestamp.text = binding.revisionToTimestamp.text
            binding.revisionToEditComment.text = StringUtil.fromHtml(it.parsedcomment.trim())

            if (it.ores != null) {
                binding.oresDamagingButton.isVisible = true
                binding.oresDamagingButton.text = "Quality: " + (it.ores?.damagingProb ?: 0).toInt() + "%"
            }
        }

        setEnableDisableTint(binding.newerIdButton, !viewModel.canGoForward)
        setEnableDisableTint(binding.olderIdButton, viewModel.revisionFromId == 0L)
        binding.newerIdButton.isEnabled = viewModel.canGoForward
        binding.olderIdButton.isEnabled = viewModel.revisionFromId != 0L

        binding.thankIcon.setImageResource(R.drawable.ic_heart_outline_24)

        binding.revisionDetailsView.isVisible = true
        binding.errorView.isVisible = false
    }

    private fun updateAfterDiffFetchSuccess() {
        binding.diffRecyclerView.isVisible = true
    }

    private fun setEnableDisableTint(view: ImageView, isDisabled: Boolean) {
        ImageViewCompat.setImageTintList(view, AppCompatResources.getColorStateList(requireContext(),
            ResourceUtil.getThemedAttributeId(requireContext(), if (isDisabled)
                R.attr.inactive_color else R.attr.secondary_color)))
    }

    private fun updateWatchButton(isWatched: Boolean, hasWatchlistExpiry: Boolean) {
        binding.watchButton.isVisible = AccountUtil.isLoggedIn
        binding.watchLabel.text = getString(if (isWatched) R.string.menu_page_unwatch else R.string.menu_page_watch)
        binding.watchIcon.setImageResource(
            if (isWatched && !hasWatchlistExpiry) {
                R.drawable.ic_star_24
            } else if (!isWatched) {
                R.drawable.ic_baseline_star_outline_24
            } else {
                R.drawable.ic_baseline_star_half_24
            }
        )
    }

    private fun showWatchlistSnackbar(expiry: WatchlistExpiry, watch: Watch) {
        isWatched = watch.watched
        hasWatchlistExpiry = expiry != WatchlistExpiry.NEVER
        updateWatchButton(isWatched, hasWatchlistExpiry)
        if (watch.unwatched) {
            FeedbackUtil.showMessage(this, getString(R.string.watchlist_page_removed_from_watchlist_snackbar, viewModel.pageTitle.displayText))
        } else if (watch.watched) {
            val snackbar = FeedbackUtil.makeSnackbar(requireActivity(),
                    getString(R.string.watchlist_page_add_to_watchlist_snackbar,
                            viewModel.pageTitle.displayText,
                            getString(expiry.stringId)))
            if (!viewModel.watchlistExpiryChanged) {
                snackbar.setAction(R.string.watchlist_page_add_to_watchlist_snackbar_action) {
                    viewModel.watchlistExpiryChanged = true
                    ExclusiveBottomSheetPresenter.show(childFragmentManager, WatchlistExpiryDialog.newInstance(expiry))
                }
            }
            snackbar.show()
        }
    }

    private fun showThankDialog() {
        val parent = FrameLayout(requireContext())
        val dialog = MaterialAlertDialogBuilder(requireActivity())
                .setView(parent)
                .setPositiveButton(R.string.thank_dialog_positive_button_text) { _, _ ->
                    viewModel.sendThanks(viewModel.pageTitle.wikiSite, viewModel.revisionToId)
                }
                .setNegativeButton(R.string.thank_dialog_negative_button_text) { _, _ ->
                    editHistoryInteractionEvent?.logThankCancel()
                }
                .create()
        dialog.layoutInflater.inflate(R.layout.view_thank_dialog, parent)
        dialog.show()
    }

    private fun showUndoDialog() {
        val dialog = UndoEditDialog(editHistoryInteractionEvent, requireActivity()) { text ->
            viewModel.revisionTo?.let {
                binding.progressBar.isVisible = true
                viewModel.undoEdit(viewModel.pageTitle, it.user, text.toString(), viewModel.revisionToId, 0)
            }
        }
        dialog.show()
    }

    private fun showRollbackDialog() {
        MaterialAlertDialogBuilder(requireActivity())
            .setMessage(R.string.revision_rollback_dialog_title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.progressBar.isVisible = true
                viewModel.revisionTo?.let {
                    viewModel.postRollback(viewModel.pageTitle, it.user)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateActionButtons() {
        binding.undoButton.isVisible = viewModel.revisionFrom != null && AccountUtil.isLoggedIn
        binding.thankButton.isEnabled = true
        binding.thankButton.isVisible = AccountUtil.isLoggedIn &&
                !AccountUtil.userName.equals(viewModel.revisionTo?.user) &&
                viewModel.revisionTo?.isAnon == false
    }

    private fun getSharableDiffUrl(): String {
        return viewModel.pageTitle.getWebApiUrl("diff=${viewModel.revisionToId}&oldid=${viewModel.revisionFromId}&variant=${viewModel.pageTitle.wikiSite.languageCode}")
    }

    override fun onExpirySelect(expiry: WatchlistExpiry) {
        viewModel.watchOrUnwatch(isWatched, expiry, false)
        ExclusiveBottomSheetPresenter.dismiss(childFragmentManager)
    }

    override fun onLinkPreviewLoadPage(title: PageTitle, entry: HistoryEntry, inNewTab: Boolean) {
        if (inNewTab) {
            startActivity(PageActivity.newIntentForNewTab(requireContext(), entry, entry.title))
        } else {
            startActivity(PageActivity.newIntentForCurrentTab(requireContext(), entry, entry.title))
        }
    }

    override fun onLinkPreviewCopyLink(title: PageTitle) {
        copyLink(title.uri)
    }

    override fun onLinkPreviewAddToList(title: PageTitle) {
        ExclusiveBottomSheetPresenter.show(childFragmentManager,
                AddToReadingListDialog.newInstance(title, InvokeSource.LINK_PREVIEW_MENU))
    }

    override fun onLinkPreviewShareLink(title: PageTitle) {
        ShareUtil.shareText(requireContext(), title)
    }

    private fun copyLink(uri: String?) {
        ClipboardUtil.setPlainText(requireContext(), text = uri)
        FeedbackUtil.showMessage(this, R.string.address_copied)
    }

    private fun callback(): Callback? {
        return FragmentUtil.getCallback(this, Callback::class.java)
    }

    companion object {
        fun newInstance(title: PageTitle, pageId: Int, revisionFrom: Long, revisionTo: Long, fromRecentEdits: Boolean): ArticleEditDetailsFragment {
            return ArticleEditDetailsFragment().apply {
                arguments = bundleOf(ArticleEditDetailsActivity.EXTRA_ARTICLE_TITLE to title,
                    ArticleEditDetailsActivity.EXTRA_PAGE_ID to pageId,
                    ArticleEditDetailsActivity.EXTRA_EDIT_REVISION_FROM to revisionFrom,
                    ArticleEditDetailsActivity.EXTRA_EDIT_REVISION_TO to revisionTo,
                    ArticleEditDetailsActivity.EXTRA_FROM_RECENT_EDITS to fromRecentEdits)
            }
        }
    }
}
