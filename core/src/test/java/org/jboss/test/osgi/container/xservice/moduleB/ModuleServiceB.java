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
package org.jboss.test.osgi.container.xservice.moduleB;

import org.jboss.test.osgi.container.xservice.bundleB.BundleServiceB;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * A SimpleService
 * 
 * @author thomas.diesler@jboss.com
 * @since 24-Apr-2009
 */
public class ModuleServiceB
{
   Bundle owner;
   
   ModuleServiceB(Bundle owner)
   {
      this.owner = owner;
   }

   public String echo(String msg)
   {
      BundleContext context = owner.getBundleContext();
      ServiceReference sref = context.getServiceReference(BundleServiceB.class.getName());
      BundleServiceB service = (BundleServiceB)context.getService(sref);
      return service.echo(msg + ":" + owner.getSymbolicName());
   }
}