/*******************************************************************************
 * Copyright (c) 2006 Vlad Dumitrescu and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Vlad Dumitrescu
 *******************************************************************************/
package org.erlide.jinterface.backend;

public interface IShellManager {

	IShell getShell(String id);

	IShell openShell(String id);

	void closeShell(String id);

}