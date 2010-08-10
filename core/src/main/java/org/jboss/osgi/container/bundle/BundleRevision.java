/*
* JBoss, Home of Professional Open Source
* Copyright 2009, Red Hat Middleware LLC, and individual contributors
* as indicated by the @author tags. See the copyright.txt file in the
* distribution for a full listing of individual contributors.
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
package org.jboss.osgi.container.bundle;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Vector;

import org.jboss.logging.Logger;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.modules.ModuleSpec;
import org.jboss.osgi.container.util.AggregatedVirtualFile;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.resolver.XModule;
import org.jboss.osgi.resolver.XModuleBuilder;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.XResolverFactory;
import org.jboss.osgi.vfs.VirtualFile;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

/**
 * A {@link BundleRevision} is responsible for the classloading and resource loading of a bundle.
 * It is associated with a resolver module ({@link XResolver}) which holds the wiring information
 * of the bundle.<p/>
 *  
 * Every time a bundle is updated a new {@link BundleRevision} is created and referenced to 
 * from the {@link InternalBundle} class. 
 * 
 * 
 * @author thomas.diesler@jboss.com
 * @author <a href="david@redhat.com">David Bosschaert</a>
 * @since 29-Jun-2010
 */
public class BundleRevision extends AbstractBundleRevision
{
   private static final Logger log = Logger.getLogger(BundleRevision.class);
   
   private XModule resolverModule;
   private VirtualFile rootFile;

   public BundleRevision(InternalBundle internalBundle, Deployment dep, int revision) throws BundleException
   {
      super(internalBundle, dep, revision);
      
      rootFile = dep.getRoot();

      if (rootFile == null)
         throw new IllegalArgumentException("Null rootFile");

      // Set the aggregated root file
      rootFile = AggregatedVirtualFile.aggregatedBundleClassPath(rootFile, getOSGiMetaData());

      // Create the resolver module
      XModuleBuilder builder = XResolverFactory.getModuleBuilder();
      resolverModule = builder.createModule(getRevisionID(), getOSGiMetaData());
      resolverModule.addAttachment(Bundle.class, internalBundle);

      // In case this bundle is a module.xml deployment, we already have a ModuleSpec
      ModuleSpec moduleSpec = dep.getAttachment(ModuleSpec.class);
      if (moduleSpec != null)
         resolverModule.addAttachment(ModuleSpec.class, moduleSpec);
   }

   @Override
   public XModule getResolverModule()
   {
      return resolverModule;
   }

   @Override
   public VirtualFile getRootFile()
   {
      return rootFile;
   }

   @Override
   public Class<?> loadClass(String className) throws ClassNotFoundException
   {
      getInternalBundle().assertNotUninstalled();
      
      // If this bundle's state is INSTALLED, this method must attempt to resolve this bundle
      if (getInternalBundle().checkResolved() == false)
         throw new ClassNotFoundException("Class '" + className + "' not found in: " + this);

      // Load the class through the module
      ModuleClassLoader loader = getBundleClassLoader();
      return loader.loadClass(className);
   }

   @Override
   public URL getResource(String path)
   {
      getInternalBundle().assertNotUninstalled();
      
      // If this bundle's state is INSTALLED, this method must attempt to resolve this bundle
      if (getInternalBundle().checkResolved() == true)
         return getBundleClassLoader().getResource(path);

      // If this bundle cannot be resolved, then only this bundle must be searched for the specified resource
      return getEntry(path);
   }

   @Override
   public Enumeration<URL> getResources(String path) throws IOException
   {
      getInternalBundle().assertNotUninstalled();
      
      // If this bundle's state is INSTALLED, this method must attempt to resolve this bundle
      if (getInternalBundle().checkResolved() == true)
      {
         Enumeration<URL> resources = getBundleClassLoader().getResources(path);
         return resources.hasMoreElements() ? resources : null;
      }

      // If this bundle cannot be resolved, then only this bundle must be searched for the specified resource
      try
      {
         VirtualFile child = getRootFile().getChild(path);
         if (child == null)
            return null;
         
         Vector<URL> vector = new Vector<URL>();
         vector.add(child.toURL());
         return vector.elements();
      }
      catch (IOException ex)
      {
         log.error("Cannot get resources: " + path, ex);
         return null;
      }
   }

   @Override
   public Enumeration<String> getEntryPaths(String path)
   {
      getInternalBundle().assertNotUninstalled();
      try
      {
         return getRootFile().getEntryPaths(path);
      }
      catch (IOException ex)
      {
         return null;
      }
   }

   @Override
   public URL getEntry(String path)
   {
      getInternalBundle().assertNotUninstalled();
      try
      {
         VirtualFile child = getRootFile().getChild(path);
         return child != null ? child.toURL() : null;
      }
      catch (IOException ex)
      {
         log.error("Cannot get entry: " + path, ex);
         return null;
      }
   }

   @Override
   public Enumeration<URL> findEntries(String path, String pattern, boolean recurse)
   {
      getInternalBundle().assertNotUninstalled();
      try
      {
         return getRootFile().findEntries(path, pattern, recurse);
      }
      catch (IOException ex)
      {
         return null;
      }
   }

   @Override
   URL getLocalizationEntry()
   {
      // TODO 
      return null;
   }
}
