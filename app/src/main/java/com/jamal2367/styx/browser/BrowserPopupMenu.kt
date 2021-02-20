package com.jamal2367.styx.browser

import com.jamal2367.styx.R
import com.jamal2367.styx.browser.activity.BrowserActivity
import com.jamal2367.styx.database.bookmark.BookmarkRepository
import com.jamal2367.styx.databinding.PopupMenuBrowserBinding
import com.jamal2367.styx.di.injector
import com.jamal2367.styx.utils.Utils
import com.jamal2367.styx.utils.isSpecialUrl
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.PopupWindow
import javax.inject.Inject

class BrowserPopupMenu : PopupWindow {

    @Inject
    internal lateinit var bookmarkModel: BookmarkRepository
    var iBinding: PopupMenuBrowserBinding

    constructor(layoutInflater: LayoutInflater, aBinding: PopupMenuBrowserBinding = BrowserPopupMenu.inflate(layoutInflater))
            : super(aBinding.root, WRAP_CONTENT, WRAP_CONTENT, true) {

        aBinding.root.context.injector.inject(this)

        iBinding = aBinding

        // Elevation just need to be high enough not to cut the effect defined in our layout
        elevation = 100F
        //
        animationStyle = R.style.AnimationMenu

        aBinding.menuItemCloseIncognito.visibility = View.GONE

        // Needed on Android 5 to make sure our pop-up can be dismissed by tapping outside and back button
        // See: https://stackoverflow.com/questions/46872634/close-popupwindow-upon-tapping-outside-or-back-button
        setBackgroundDrawable(ColorDrawable())

        // Hide incognito menu item if we are already incognito
        if ((aBinding.root.context as BrowserActivity).isIncognito()) {
            aBinding.menuItemIncognito.visibility = View.GONE
            // No sessions in incognito mode
            aBinding.menuItemSessions.visibility = View.GONE
            // Show close incognito mode button
            aBinding.menuItemCloseIncognito.visibility = View.VISIBLE
        }

        //val radius: Float = getResources().getDimension(R.dimen.default_corner_radius) //32dp

        /*
        // TODO: That fixes the corner but leaves a square shadow behind
        val toolbar: AppBarLayout = view.findViewById(R.id.header)
        val materialShapeDrawable = toolbar.background as MaterialShapeDrawable
        materialShapeDrawable.shapeAppearanceModel = materialShapeDrawable.shapeAppearanceModel
                .toBuilder()
                .setAllCorners(CornerFamily.ROUNDED, Utils.dpToPx(16F).toFloat())
                .build()
         */

    }


    fun onMenuItemClicked(menuView: View, onClick: () -> Unit) {
        menuView.setOnClickListener {
            onClick()
            dismiss()
        }
    }

    fun show(aAnchor: View) {

        (contentView.context as BrowserActivity).tabsManager.let {
            // Set desktop mode checkbox according to current tab
            iBinding.menuItemDesktopMode.isChecked = it.currentTab?.desktopMode ?: false

            it.currentTab?.let { tab ->
                // Let user add multiple times the same URL I guess, for now anyway
                // Blocking it is not nice and subscription is more involved I guess
                // See BookmarksDrawerView.updateBookmarkIndicator
                //contentView.menuItemAddBookmark.visibility = if (bookmarkModel.isBookmark(tab.url).blockingGet() || tab.url.isSpecialUrl()) View.GONE else View.VISIBLE
                iBinding.menuItemAddBookmark.visibility = if (tab.url.isSpecialUrl()) View.GONE else View.VISIBLE
            }
        }

        //showAsDropDown(aAnchor, 0,-aAnchor.height)

        // Get our anchor location
        val anchorLoc = IntArray(2)
        aAnchor.getLocationInWindow(anchorLoc)
        // Show our popup menu from the right side of the screen below our anchor
        showAtLocation(aAnchor, Gravity.TOP or Gravity.RIGHT,
                // Offset from the right screen edge
                Utils.dpToPx(10F),
                // Above our anchor
                anchorLoc[1])

    }

    companion object {

        fun inflate(layoutInflater: LayoutInflater): PopupMenuBrowserBinding {
            return PopupMenuBrowserBinding.inflate(layoutInflater)
        }

    }
}

