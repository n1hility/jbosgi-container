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
package org.jboss.osgi.msc.bundle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;
import org.jboss.modules.AssertionSetting;
import org.jboss.modules.DependencySpec;
import org.jboss.modules.Module;
import org.jboss.modules.Module.Flag;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.modules.ModuleContentLoader;
import org.jboss.modules.ModuleContentLoader.Builder;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.ModuleLoader;
import org.jboss.modules.ModuleLoaderSelector;
import org.jboss.modules.ModuleLoaderSpec;
import org.jboss.modules.ModuleSpec;
import org.jboss.modules.SystemModuleClassLoader;
import org.jboss.osgi.msc.loading.VirtualFileResourceLoader;
import org.jboss.osgi.msc.metadata.OSGiMetaData;
import org.jboss.osgi.vfs.VirtualFile;
import org.osgi.framework.Constants;

/**
 * Build the {@link ModuleSpec} from {@link OSGiMetaData}.
 * 
 * @author thomas.diesler@jboss.com
 * @since 29-Jun-2010
 */
public class ModuleManager extends ModuleLoader
{
   // Provide logging
   private static final Logger log = Logger.getLogger(ModuleManager.class);

   // The registered modules
   // [FEEDBACK] ModuleManager needs to maintain this duplicate map to remove modules on Bundle.uninstall() 
   private Map<ModuleIdentifier, Module> modules = Collections.synchronizedMap(new LinkedHashMap<ModuleIdentifier, Module>());
   // The framework module identifier
   private ModuleIdentifier frameworkIdentifier;
   // The framework module
   private Module frameworkModule;

   public ModuleManager()
   {
      // Make sure this ModuleLoader is used
      // This also registers the URLStreamHandlerFactory
      final ModuleLoader moduleLoader = this;
      Module.setModuleLoaderSelector(new ModuleLoaderSelector()
      {
         @Override
         public ModuleLoader getCurrentLoader()
         {
            return moduleLoader;
         }
      });
   }

   public ModuleIdentifier getFrameworkModuleIdentifier()
   {
      if (frameworkIdentifier == null)
         frameworkIdentifier = new ModuleIdentifier("jbosgi", Constants.SYSTEM_BUNDLE_SYMBOLICNAME, null);

      return frameworkIdentifier;
   }

   public ModuleSpec createModuleSpec(OSGiMetaData metadata, VirtualFile rootFile)
   {
      String symbolicName = metadata.getBundleSymbolicName();
      String version = metadata.getBundleVersion();
      ModuleIdentifier moduleIdentifier = new ModuleIdentifier("jbosgi", symbolicName, version);
      ModuleSpec moduleSpec = new ModuleSpec(moduleIdentifier);

      // Add the framework module as required dependency
      List<DependencySpec> dependencies = moduleSpec.getDependencies();
      DependencySpec frameworkDependency = new DependencySpec();
      frameworkDependency.setModuleIdentifier(getFrameworkModuleIdentifier());
      frameworkDependency.setExport(true);
      dependencies.add(frameworkDependency);

      // Add the bundle's {@link ResourceLoader}
      Builder builder = ModuleContentLoader.build();
      builder.add(moduleIdentifier.toString(), new VirtualFileResourceLoader(rootFile));
      moduleSpec.setContentLoader(builder.create());

      log.debug("Created ModuleSpec: " + moduleSpec);
      return moduleSpec;
   }

   @Override
   protected Module findModule(ModuleIdentifier moduleIdentifier) throws ModuleLoadException
   {
      return modules.get(moduleIdentifier);
   }

   public Module createFrameworkModule() throws ModuleLoadException
   {
      if (frameworkModule != null)
         throw new IllegalStateException("Framework module already created");

      final ModuleLoader moduleLoader = this;
      frameworkModule = new Module(frameworkIdentifier, new ModuleLoaderSpec()
      {
         @Override
         public ModuleLoader getModuleLoader(Module module)
         {
            return moduleLoader;
         }
         
         @Override
         public ModuleClassLoader getModuleClassLoader(Module module)
         {
            SystemModuleClassLoader smcl = new SystemModuleClassLoader(module, Collections.<Flag>emptySet(), AssertionSetting.INHERIT)
            {
               @Override
               protected Set<String> getExportedPaths()
               {
                  Set<String> exportedPaths = super.getExportedPaths();
                  // [TODO] read these from external properties
                  exportedPaths.add("org/osgi/framework");
                  return exportedPaths;
               }
            };
            return smcl;
         }
      });
      registerModule(frameworkModule);
      return frameworkModule;
   }

   public Module createModule(ModuleSpec moduleSpec) throws ModuleLoadException
   {
      if (frameworkModule == null)
         createFrameworkModule();
      
      Module module = defineModule(moduleSpec);
      registerModule(module);
      return module;
   }

   private void registerModule(Module module) throws ModuleLoadException
   {
      modules.put(module.getIdentifier(), module);
   }

   public void unregisterModule(Module module)
   {
      modules.remove(module.getIdentifier());
   }
}