package com.dearmoon.shield.ui

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.dearmoon.shield.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * BottomSheet that slides up to 80% height, showing a beautiful 
 * organic scanning animation before enabling the SHIELD protection.
 */
class ScanningBottomSheet : BottomSheetDialogFragment() {

    interface OnScanCompleteListener {
        fun onScanComplete()
    }
    
    private var scanCompleteListener: OnScanCompleteListener? = null

    fun setOnScanCompleteListener(listener: OnScanCompleteListener) {
        scanCompleteListener = listener
    }
    
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.layout_scanning_bottom_sheet, container, false)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                // Set bottom sheet background to transparent to allow rounded corners in XML
                bottomSheet.setBackgroundResource(android.R.color.transparent)
                
                val behavior = BottomSheetBehavior.from(bottomSheet)
                
                // Set to ~80% of screen height
                val displayMetrics = resources.displayMetrics
                val height = (displayMetrics.heightPixels * 0.80).toInt()
                
                val layoutParams = bottomSheet.layoutParams
                layoutParams.height = height
                bottomSheet.layoutParams = layoutParams
                
                behavior.peekHeight = height
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = false // Prevent throwing away while scanning
            }
        }
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        isCancelable = false // User must wait
        
        val scanView = view.findViewById<OrganicScanView>(R.id.organicScanView)
        val statusBtn = view.findViewById<ScrambleTextButton>(R.id.tvScanStatus)
        val subStatus = view.findViewById<TextView>(R.id.tvScanSubStatus)
        
        scanView.startScan()
        statusBtn.scrambleText("Initiating Scan")
        subStatus.text = "Initializing verification framework..."
        
        val handler = Handler(Looper.getMainLooper())
        
        // Sequence of scanning states
        handler.postDelayed({
            statusBtn.scrambleText("Validating Sectors")
            subStatus.text = "Checking file permissions and network integrity..."
        }, 1800)
        
        handler.postDelayed({
            statusBtn.scrambleText("Securing Tunnel")
            subStatus.text = "Establishing VPN node and packet filtering rules..."
        }, 3600)
        
        handler.postDelayed({
            statusBtn.scrambleText("SYSTEM SECURE")
            subStatus.text = "Real-time threat monitoring is actively blocking attacks"
            
            // Wait 1.4s then trigger the callback and close
            handler.postDelayed({
                scanCompleteListener?.onScanComplete()
                dismissAllowingStateLoss()
            }, 1400)
            
        }, 5400)
    }
}
