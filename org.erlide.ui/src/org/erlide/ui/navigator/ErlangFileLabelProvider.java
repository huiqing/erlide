package org.erlide.ui.navigator;

import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.IDescriptionProvider;
import org.erlide.core.erlang.IErlElement;

public class ErlangFileLabelProvider extends LabelProvider implements
		ILabelProvider, IDescriptionProvider {

	public ErlangFileLabelProvider() {
		super();
	}

	@Override
	public Image getImage(Object element) {
		if (element instanceof IErlElement) {
			return PlatformUI.getWorkbench().getSharedImages().getImage(
					ISharedImages.IMG_OBJS_INFO_TSK);

		}
		if (element instanceof ErlangFileTreeData) {
			return PlatformUI.getWorkbench().getSharedImages().getImage(
					ISharedImages.IMG_OBJS_INFO_TSK);

		}
		return null;
	}

	@Override
	public String getText(Object element) {
		if (element instanceof ErlangFileTreeData) {
			ErlangFileTreeData data = (ErlangFileTreeData) element;
			return data.getValue();
		}
		if (element instanceof IErlElement) {
			IErlElement data = (IErlElement) element;
			return data.toString();// + "= " + data.getValue();
		}
		return null;
	}

	public String getDescription(Object anElement) {
		if (anElement instanceof IErlElement) {
			IErlElement data = (IErlElement) anElement;
			return "Property: " + data.toString(); //$NON-NLS-1$
		}
		if (anElement instanceof ErlangFileTreeData) {
			ErlangFileTreeData data = (ErlangFileTreeData) anElement;
			return "Property: " + data.getValue();
		}
		return null;
	}

	@Override
	public void addListener(ILabelProviderListener listener) {
		// TODO Auto-generated method stub

	}

	@Override
	public void dispose() {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean isLabelProperty(Object element, String property) {
		// TODO Auto-generated method stub
		return true;
	}

	@Override
	public void removeListener(ILabelProviderListener listener) {
		// TODO Auto-generated method stub

	}

}
