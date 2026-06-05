package com.app.common.modules.queuemanager.helpers;

import java.util.function.Predicate;

import javafx.scene.Node;
import javafx.scene.control.TreeTableRow;

/**
 * Keeps disclosure arrows vertically centered for expandable queue group rows.
 */
public class GroupNodeTreeRow<T> extends TreeTableRow<T> {

    private final Predicate<T> groupNodePredicate;

    public GroupNodeTreeRow(Predicate<T> groupNodePredicate) {
        this.groupNodePredicate = groupNodePredicate;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();

        Node disclosureNode = lookup(".tree-disclosure-node");
        if (disclosureNode == null) {
            return;
        }

        T item = getItem();
        boolean centerForGroupNode = item != null && groupNodePredicate.test(item)
                && getTreeItem() != null && !getTreeItem().isLeaf();
        if (!centerForGroupNode) {
            disclosureNode.setTranslateY(0);
            return;
        }

        double centeredOffset = (getHeight() - disclosureNode.getLayoutBounds().getHeight()) / 2.0
                - disclosureNode.getLayoutY();
        disclosureNode.setTranslateY(centeredOffset);
    }
}
