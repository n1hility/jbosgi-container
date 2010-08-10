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
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.logging.Logger;
import org.jboss.osgi.container.plugin.BundleDeploymentPlugin;
import org.jboss.osgi.container.plugin.FrameworkEventsPlugin;
import org.jboss.osgi.container.plugin.StartLevelPlugin;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.modules.ModuleActivator;
import org.jboss.osgi.resolver.XModule;
import org.jboss.osgi.resolver.XWire;
import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VirtualFile;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.Version;
import org.osgi.service.packageadmin.PackageAdmin;

/**
 * This is the internal implementation of a Bundle. The logic related to loading of classes
 * and resources is delegated to the current {@link BundleRevision}. As bundles can be updated there
 * can be multiple bundle revisions.<p/>
 * 
 * The InternalBundle contains two Bundle Revisions: the current revision and the latest revision.
 * These concepts relate to updating of bundles. When a bundle is updated a new revision is created
 * and assigned to the latest revision. However, if the current revision is used by another bundle
 * (e.g. via a package import) the current revision is kept pointing at the revision before the update
 * and the updated bundle is not yet made available in the framework. 
 * In that case, the updated bundle can be enabled by calling 
 * {@link PackageAdmin#refreshPackages(Bundle[] bundles)} which will update the current revision
 * to the latest revision.<p/>
 * 
 * In addition other bundle-specific functionality is handled here, such as Start Level and the
 * Bundle Activator and internal implementations of lifecycle management. 
 * 
 * @author thomas.diesler@jboss.com
 * @author <a href="david@redhat.com">David Bosschaert</a>
 */
public class InternalBundle extends AbstractBundle
{
   private static final Logger log = Logger.getLogger(InternalBundle.class);

   private BundleActivator bundleActivator;
   private final String location;
   // The current revision is the bundle revision used by the system. This is what other bundle use if they import packages.
   private AbstractBundleRevision currentRevision;
   // The latest revision is the newest revision of the bundle, which could be newer than the current revision if updated.
   private AbstractBundleRevision latestRevision;
   private int startLevel = StartLevelPlugin.BUNDLE_STARTLEVEL_UNSPECIFIED;
   private boolean persistentlyStarted;
   private AtomicInteger revisionCounter = new AtomicInteger(0);

   InternalBundle(BundleManager bundleManager, Deployment deployment) throws BundleException
   {
      super(bundleManager, deployment.getSymbolicName());
      location = deployment.getLocation();
      if (location == null)
         throw new IllegalArgumentException("Null location");

      latestRevision = currentRevision = new BundleRevision(this, deployment, revisionCounter.getAndIncrement());

      StartLevelPlugin sl = getBundleManager().getOptionalPlugin(StartLevelPlugin.class);
      if (sl != null)
         startLevel = sl.getInitialBundleStartLevel();
   }

   /**
    * Assert that the given bundle is an instance of InternalBundle
    * @throws IllegalArgumentException if the given bundle is not an instance of InternalBundle
    */
   public static InternalBundle assertBundleState(Bundle bundle)
   {
      AbstractBundle bundleState = AbstractBundle.assertBundleState(bundle);

      if (bundleState instanceof InternalBundle == false)
         throw new IllegalArgumentException("Not an InternalBundle: " + bundleState);

      return (InternalBundle)bundleState;
   }

   boolean checkResolved()
   {
      // If this bundle's state is INSTALLED, this method must attempt to resolve this bundle 
      // If this bundle cannot be resolved, a Framework event of type FrameworkEvent.ERROR is fired 
      // containing a BundleException with details of the reason this bundle could not be resolved. 
      if (getState() == Bundle.INSTALLED)
      {
         try
         {
            getResolverPlugin().resolve((AbstractBundle)this);
         }
         catch (BundleException ex)
         {
            FrameworkEventsPlugin plugin = getFrameworkEventsPlugin();
            plugin.fireFrameworkEvent(this, FrameworkEvent.ERROR, ex);
         }
      }

      // If the bundle has a ClassLoader it is in state {@link Bundle#RESOLVED}
      return currentRevision.getBundleClassLoader() != null;
   }

   @Override
   AbstractBundleContext createContextInternal()
   {
      return new InternalBundleContext(this, null);
   }

   @Override
   public String getLocation()
   {
      return location;
   }

   public int getStartLevel()
   {
      return startLevel;
   }

   public void setStartLevel(int sl)
   {
      startLevel = sl;
   }

   public boolean isPersistentlyStarted()
   {
      return persistentlyStarted;
   }

   public void setPersistentlyStarted(boolean started)
   {
      persistentlyStarted = started;
   }

   /** 
    * This method gets called by Package Admin when the bundle needs to be refreshed.
    */
   public void refresh() throws BundleException
   {
      if (latestRevision.equals(currentRevision))
         return;

      if (getState() == Bundle.UNINSTALLED)
      {
         // TODO possibly do some cleanup, or is everything properly dereferenced at this stage?
         return;
      }

      getResolverPlugin().removeBundle(this);
      currentRevision = latestRevision;
      getResolverPlugin().addBundle(this);
   }

   @Override
   void startInternal(int options) throws BundleException
   {
      // If this bundle's state is ACTIVE then this method returns immediately.
      if (getState() == Bundle.ACTIVE)
         return;

      if ((options & Bundle.START_TRANSIENT) == 0)
         setPersistentlyStarted(true);

      StartLevelPlugin plugin = getBundleManager().getOptionalPlugin(StartLevelPlugin.class);
      if (plugin != null && plugin.getStartLevel() < getStartLevel())
         // Not at the required start level yet. This bundle will be started later once
         // the required start level has been reached.
         // TODO the spec says that we need to throw a BundleException here... 
         return;

      OSGiMetaData osgiMetaData = getOSGiMetaData();
      if (osgiMetaData == null)
         throw new IllegalStateException("Cannot obtain OSGi meta data");

      // Resolve this bundles 
      if (getState() == Bundle.INSTALLED)
         getResolverPlugin().resolve(this);

      // The BundleActivator.start(org.osgi.framework.BundleContext) method of this bundle's BundleActivator, if one is specified, is called. 
      try
      {
         // Create the bundle context
         createBundleContext();

         // This bundle's state is set to STARTING
         changeState(Bundle.STARTING);

         // Do we have a bundle activator
         String bundleActivatorClassName = osgiMetaData.getBundleActivator();
         if (bundleActivatorClassName != null)
         {
            Object result = loadClass(bundleActivatorClassName).newInstance();
            if (result instanceof ModuleActivator)
            {
               bundleActivator = new ModuleActivatorBridge((ModuleActivator)result);
               bundleActivator.start(getBundleContext());
            }
            else if (result instanceof BundleActivator)
            {
               bundleActivator = (BundleActivator)result;
               bundleActivator.start(getBundleContext());
            }
            else
            {
               throw new BundleException(bundleActivatorClassName + " is not an implementation of " + BundleActivator.class.getName());
            }
         }

         if (getState() != STARTING)
            throw new BundleException("Bundle has been uninstalled: " + this);

         changeState(ACTIVE);
      }

      // If the BundleActivator is invalid or throws an exception then:
      //   * This bundle's state is set to STOPPING.
      //   * A bundle event of type BundleEvent.STOPPING is fired.
      //   * Any services registered by this bundle must be unregistered.
      //   * Any services used by this bundle must be released.
      //   * Any listeners registered by this bundle must be removed.
      //   * This bundle's state is set to RESOLVED.
      //   * A bundle event of type BundleEvent.STOPPED is fired.
      //   * A BundleException is then thrown.
      catch (Throwable t)
      {
         // This bundle's state is set to STOPPING
         // A bundle event of type BundleEvent.STOPPING is fired
         changeState(STOPPING);

         // Any services registered by this bundle must be unregistered.
         // Any services used by this bundle must be released.
         // Any listeners registered by this bundle must be removed.
         stopInternal(options);

         // This bundle's state is set to RESOLVED
         // A bundle event of type BundleEvent.STOPPED is fired
         destroyBundleContext();
         changeState(RESOLVED);

         if (t instanceof BundleException)
            throw (BundleException)t;

         throw new BundleException("Cannot start bundle: " + this, t);
      }
   }

   @Override
   public void stop(int options) throws BundleException
   {
      if ((options & Bundle.STOP_TRANSIENT) == 0)
         setPersistentlyStarted(false);

      super.stop(options);
   }

   @Override
   void stopInternal(int options) throws BundleException
   {
      // If this bundle's state is UNINSTALLED then an IllegalStateException is thrown. 
      if (getState() == Bundle.UNINSTALLED)
         throw new IllegalStateException("Bundle already uninstalled: " + this);

      // [TODO] If this bundle is in the process of being activated or deactivated then this method must wait for activation or deactivation 
      // to complete before continuing. If this does not occur in a reasonable time, a BundleException is thrown to indicate this bundle 
      // was unable to be stopped.

      // [TODO] If the STOP_TRANSIENT option is not set then then set this bundle's persistent autostart setting to to Stopped. 
      // When the Framework is restarted and this bundle's autostart setting is Stopped, this bundle must not be automatically started. 

      // If this bundle's state is not STARTING or ACTIVE then this method returns immediately
      if (getState() != Bundle.STARTING && getState() != Bundle.ACTIVE)
         return;

      // This bundle's state is set to STOPPING
      // A bundle event of type BundleEvent.STOPPING is fired
      int priorState = getState();
      changeState(STOPPING);

      // If this bundle's state was ACTIVE prior to setting the state to STOPPING, 
      // the BundleActivator.stop(org.osgi.framework.BundleContext) method of this bundle's BundleActivator, if one is specified, is called. 
      // If that method throws an exception, this method must continue to stop this bundle and a BundleException must be thrown after completion 
      // of the remaining steps.
      Throwable rethrow = null;
      if (priorState == Bundle.ACTIVE)
      {
         if (bundleActivator != null && getBundleContext() != null)
         {
            try
            {
               if (bundleActivator instanceof ModuleActivatorBridge)
               {
                  bundleActivator.stop(getBundleContextInternal());
               }
               else
               {
                  bundleActivator.stop(getBundleContext());
               }
            }
            catch (Throwable t)
            {
               rethrow = t;
            }
         }
      }

      // Any services registered by this bundle must be unregistered
      for (ServiceState serviceState : getRegisteredServicesInternal())
         serviceState.unregister();

      // [TODO] Any listeners registered by this bundle must be removed

      // If this bundle's state is UNINSTALLED, because this bundle was uninstalled while the 
      // BundleActivator.stop method was running, a BundleException must be thrown
      if (getState() == Bundle.UNINSTALLED)
         throw new BundleException("Bundle uninstalled during activator stop: " + this);

      // This bundle's state is set to RESOLVED
      // A bundle event of type BundleEvent.STOPPED is fired
      destroyBundleContext();
      changeState(RESOLVED);

      if (rethrow != null)
         throw new BundleException("Error during stop of bundle: " + this, rethrow);
   }

   @Override
   void updateInternal(InputStream input) throws BundleException
   {
      // Not checking that the bundle is uninstalled as that already happens in super.update()
      boolean restart = false;
      int state = getState();
      if (state == Bundle.ACTIVE || state == Bundle.STARTING || state == Bundle.STOPPING)
      {
         // If this bundle's state is ACTIVE, STARTING  or STOPPING, this bundle is stopped as described in the Bundle.stop method. 
         // If Bundle.stop throws an exception, the exception is rethrown terminating the update.
         stopInternal(Bundle.STOP_TRANSIENT);
         if (state != Bundle.STOPPING)
            restart = true;
      }
      unresolve();

      try
      {
         latestRevision = createNewBundleRevision(input);
         if (!someoneIsWiredToMe())
            // If this bundle has exported any packages that are imported by another bundle, these packages must not be updated. 
            // Instead, the previous package version must remain exported until the PackageAdmin.refreshPackages method has been 
            // has been called or the Framework is relaunched. 
            refresh();
      }
      catch (Exception e)
      {
         // If the Framework is unable to install the updated version of this bundle, the original 
         // version of this bundle must be restored and a BundleException must be thrown after 
         // completion of the remaining steps.
         BundleException be = new BundleException("Problem updating bundle");
         be.initCause(e);

         if (restart)
            startInternal(Bundle.START_TRANSIENT);

         throw be;
      }

      getFrameworkEventsPlugin().fireBundleEvent(getBundleWrapper(), BundleEvent.UPDATED);
      if (restart)
      {
         // If this bundle's state was originally ACTIVE or STARTING, the updated bundle is started as described in the Bundle.start method. 
         // If Bundle.start throws an exception, a Framework event of type FrameworkEvent.ERROR is fired containing the exception
         try
         {
            startInternal(Bundle.START_TRANSIENT);
         }
         catch (BundleException e)
         {
            getFrameworkEventsPlugin().fireFrameworkEvent(getBundleWrapper(), FrameworkEvent.ERROR, e);
         }
      }
   }

   public void ensureNewRevision() throws BundleException
   {
      if (currentRevision.equals(latestRevision) == false)
         return;

      try
      {
         latestRevision = createNewBundleRevision(null);
      }
      catch (Exception e)
      {
         throw new BundleException("Problem creating new revision of " + this, e);
      }
   }

   private boolean someoneIsWiredToMe()
   {
      XModule currentResolverModule = currentRevision.getResolverModule();
      for (AbstractBundle ab : getBundleManager().getBundles())
      {
         XModule module = ab.getResolverModule();
         if (module != null)
         {
            List<XWire> wires = module.getWires();
            if (wires != null)
            {
               for (XWire wire : wires)
               {
                  if (wire.getExporter().equals(currentResolverModule))
                     return true;
               }
            }
         }
      }
      return false;
   }

   /**
    * Creates a new Bundle Revision when the bundle is updated. Multiple Bundle Revisions 
    * can co-exist at the same time.
    * @param input The stream to create the bundle revision from or <tt>null</tt>
    * if the new revision needs to be created from the same location as where the bundle
    * was initially installed.
    * @return A new Bundle Revision.
    * @throws Exception If the bundle cannot be read, or if the update attempt to change the BSN.
    */
   private BundleRevision createNewBundleRevision(InputStream input) throws Exception
   {
      BundleManager bm = getBundleManager();
      URL locationURL;

      // If the specified InputStream is null, the Framework must create the InputStream from 
      // which to read the updated bundle by interpreting, in an implementation dependent manner, 
      // this bundle's Bundle-UpdateLocation Manifest header, if present, or this bundle's 
      // original location.
      if (input == null)
      {
         String ul = getOSGiMetaData().getHeader(Constants.BUNDLE_UPDATELOCATION);
         if (ul != null)
            input = new URL(ul).openStream();
      }

      if (input != null)
         locationURL = bm.storeBundleStream(input);
      else
         locationURL = currentRevision.getContentRoots().get(0).getStreamURL();

      BundleDeploymentPlugin plugin = bm.getPlugin(BundleDeploymentPlugin.class);
      VirtualFile newRootFile = AbstractVFS.getRoot(locationURL);
      Deployment dep = plugin.createDeployment(newRootFile, getLocation());
      OSGiMetaData md = plugin.createOSGiMetaData(dep);
      dep.addAttachment(OSGiMetaData.class, md);

      if (md.getBundleSymbolicName().equals(getSymbolicName()) == false)
         log.infof("Ignoring update of symbolic name: %s", md.getBundleSymbolicName());

      return new BundleRevision(this, dep, revisionCounter.getAndIncrement());
   }

   @Override
   void uninstallInternal() throws BundleException
   {
      BundleManager bundleManager = getBundleManager();
      if (bundleManager.getBundleById(getBundleId()) == null)
         throw new BundleException("Not installed: " + this);

      // If this bundle's state is ACTIVE, STARTING or STOPPING, this bundle is stopped 
      // as described in the Bundle.stop method.
      int state = getState();
      if (state == Bundle.ACTIVE || state == Bundle.STARTING || state == Bundle.STOPPING)
      {
         try
         {
            stopInternal(0);
         }
         catch (Exception ex)
         {
            // If Bundle.stop throws an exception, a Framework event of type FrameworkEvent.ERROR is
            // fired containing the exception
            bundleManager.fireError(this, "Error stopping bundle: " + this, ex);
         }
      }

      bundleManager.removeBundleState(this);
   }

   public void unresolve() throws BundleException
   {
      assertNotUninstalled();

      changeState(Bundle.INSTALLED);
   }

   // Methods delegated to the current revision.
   @Override
   public URL getResource(String name)
   {
      return currentRevision.getResource(name);
   }

   @Override
   @SuppressWarnings("rawtypes")
   public Class loadClass(String name) throws ClassNotFoundException
   {
      return currentRevision.loadClass(name);
   }

   @Override
   @SuppressWarnings("rawtypes")
   public Enumeration getResources(String name) throws IOException
   {
      return currentRevision.getResources(name);
   }

   @Override
   @SuppressWarnings("rawtypes")
   public Enumeration getEntryPaths(String path)
   {
      return currentRevision.getEntryPaths(path);
   }

   @Override
   public URL getEntry(String path)
   {
      return currentRevision.getEntry(path);
   }

   @Override
   @SuppressWarnings("rawtypes")
   public Enumeration findEntries(String path, String filePattern, boolean recurse)
   {
      return currentRevision.findEntries(path, filePattern, recurse);
   }

   @Override
   URL getLocalizationEntry(String entryPath)
   {
      return currentRevision.getLocalizationEntry();
   }

   @Override
   public Version getVersion()
   {
      return currentRevision.getVersion();
   }

   @Override
   OSGiMetaData getOSGiMetaData()
   {
      return currentRevision.getOSGiMetaData();
   }

   @Override
   public XModule getResolverModule()
   {
      return currentRevision.getResolverModule();
   }

   @Override
   public List<VirtualFile> getContentRoots()
   {
      return currentRevision.getContentRoots();
   }
}
