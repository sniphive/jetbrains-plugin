package com.sniphive.idea.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/**
 * FlowLayout variant that reports a wrapped preferred height.
 *
 * Plain FlowLayout lays components out on multiple rows, but its preferred size
 * is calculated as one long row. Inside a scroll pane that can make the view
 * wider than the dialog. This layout keeps the width bounded and grows height.
 */
class WrapLayout(
    align: Int = LEFT,
    hgap: Int = 5,
    vgap: Int = 5
) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension {
        return layoutSize(target, preferred = true)
    }

    override fun minimumLayoutSize(target: Container): Dimension {
        val minimum = layoutSize(target, preferred = false)
        minimum.width -= hgap + 1
        return minimum
    }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val insets = target.insets
            val targetWidth = when {
                target.width > 0 -> target.width
                target.parent?.width != null && target.parent.width > 0 -> target.parent.width
                else -> Int.MAX_VALUE
            }

            val maxWidth = targetWidth - insets.left - insets.right - hgap * 2
            var rowWidth = 0
            var rowHeight = 0
            val size = Dimension(0, 0)

            for (component in target.components) {
                if (!component.isVisible) {
                    continue
                }

                val componentSize = if (preferred) component.preferredSize else component.minimumSize
                val nextRowWidth = if (rowWidth == 0) componentSize.width else rowWidth + hgap + componentSize.width

                if (nextRowWidth > maxWidth && rowWidth > 0) {
                    addRow(size, rowWidth, rowHeight)
                    rowWidth = componentSize.width
                    rowHeight = componentSize.height
                } else {
                    rowWidth = nextRowWidth
                    rowHeight = maxOf(rowHeight, componentSize.height)
                }
            }

            addRow(size, rowWidth, rowHeight)

            size.width += insets.left + insets.right + hgap * 2
            size.height += insets.top + insets.bottom + vgap * 2

            return size
        }
    }

    private fun addRow(size: Dimension, rowWidth: Int, rowHeight: Int) {
        size.width = maxOf(size.width, rowWidth)
        if (size.height > 0) {
            size.height += vgap
        }
        size.height += rowHeight
    }
}
