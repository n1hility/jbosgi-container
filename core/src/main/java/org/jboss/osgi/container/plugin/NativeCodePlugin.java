/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.osgi.container.plugin;

import org.jboss.osgi.container.bundle.AbstractUserBundle;
import org.jboss.osgi.deployment.deployer.Deployment;


/**
 * The bundle native code plugin
 * 
 * @author thomas.diesler@jboss.com
 * @since 11-Aug-2010
 */
public interface NativeCodePlugin extends Plugin
{
   void deployNativeCode(Deployment deployment);
   
   void resolveNativeCode(AbstractUserBundle depBundle);
}