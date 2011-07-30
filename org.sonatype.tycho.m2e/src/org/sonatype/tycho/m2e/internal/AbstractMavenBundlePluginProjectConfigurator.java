/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.sonatype.tycho.m2e.internal;

import java.io.File;
import java.util.List;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.Scanner;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.configurator.AbstractBuildParticipant;
import org.eclipse.m2e.core.project.configurator.AbstractProjectConfigurator;
import org.eclipse.m2e.core.project.configurator.MojoExecutionBuildParticipant;
import org.sonatype.plexus.build.incremental.BuildContext;

public abstract class AbstractMavenBundlePluginProjectConfigurator
    extends AbstractProjectConfigurator
{
    public static final String MOJO_GROUP_ID = "org.apache.felix";

    public static final String MOJO_ARTIFACT_ID = "maven-bundle-plugin";

    static boolean isOsgiBundleProject( IMavenProjectFacade facade, IProgressMonitor monitor )
        throws CoreException
    {
        List<Plugin> plugins = facade.getMavenProject( monitor ).getBuildPlugins();
        if ( plugins != null )
        {
            for ( Plugin plugin : plugins )
            {
                if ( isMavenBundlePluginMojo( plugin ) && !plugin.getExecutions().isEmpty() )
                {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isMavenBundlePluginMojo( MojoExecution execution )
    {
        return isMavenBundlePluginMojo( execution.getGroupId(), execution.getArtifactId() );
    }

    static boolean isMavenBundlePluginMojo( Plugin plugin )
    {
        return isMavenBundlePluginMojo( plugin.getGroupId(), plugin.getArtifactId() );
    }

    static boolean isMavenBundlePluginMojo( String groupId, String artifactId )
    {
        return MOJO_GROUP_ID.equals( groupId ) && MOJO_ARTIFACT_ID.equals( artifactId );
    }

    static AbstractBuildParticipant getBuildParticipant( MojoExecution execution )
    {
        if ( !isMavenBundlePluginMojo( execution ) )
        {
            throw new IllegalArgumentException();
        }

        if ( "bundle".equals( execution.getGoal() ) )
        {
            // do not generate complete bundle. this is both slow and can produce unexpected workspace changes
            // that will trigger unexpected/endless workspace build.
            // we rely on the fact that ManifestPlugin mojo extends BundlePlugin and does not introduce any
            // additional parameters, so can run manifest goal in place of bundle goal.
            MojoDescriptor descriptor = execution.getMojoDescriptor().clone();
            descriptor.setGoal( "manifest" );
            descriptor.setImplementation( "org.apache.felix.bundleplugin.ManifestPlugin" );
            MojoExecution _execution =
                new MojoExecution( execution.getPlugin(), "manifest", "tycho-m2e:" + execution.getExecutionId()
                    + ":manifest" );
            _execution.setConfiguration( execution.getConfiguration() );
            _execution.setMojoDescriptor( descriptor );
            _execution.setLifecyclePhase( execution.getLifecyclePhase() );
            execution = _execution;
        }

        return new MojoExecutionBuildParticipant( execution, true )
        {
            @Override
            public Set<IProject> build( int kind, IProgressMonitor monitor )
                throws Exception
            {
                BuildContext buildContext = getBuildContext();
                MavenProject mavenProject = getMavenProjectFacade().getMavenProject( monitor );

                if ( IncrementalProjectBuilder.FULL_BUILD != kind )
                {
                    // TODO
                    // The check above is necessary to handle the case when a wrapper project does not have any sources
                    // but the manifest needs to be regenerated because of dependency change. Need a better way.

                    Scanner ds = buildContext.newScanner( new File( mavenProject.getBuild().getOutputDirectory() ) );
                    ds.scan();
                    String[] includedFiles = ds.getIncludedFiles();
                    if ( includedFiles == null || includedFiles.length <= 0 )
                    {
                        return null;
                    }
                }

                Set<IProject> projects = super.build( kind, monitor );
                IProject project = getMavenProjectFacade().getProject();

                IContainer metainf = PDEProjectHelper.getManifestLocation( project );
                if ( metainf == null || metainf instanceof IProject )
                {
                    metainf = project.getFolder( "META-INF" );
                }
                metainf.refreshLocal( IResource.DEPTH_INFINITE, monitor );

                return projects;
            }
        };
    }

    /**
     * Returns project relative path of generated OSGi bundle manifest
     */
    static IPath getManifestPath( IMavenProjectFacade facade, MavenSession session, IProgressMonitor monitor )
        throws CoreException
    {
        IMaven maven = MavenPlugin.getMaven();
        for ( MojoExecution execution : facade.getMojoExecutions( MOJO_GROUP_ID, MOJO_ARTIFACT_ID, monitor, "manifest",
                                                                  "bundle" ) )
        {
            File location = maven.getMojoParameterValue( session, execution, "manifestLocation", File.class );
            if ( location != null )
            {
                return facade.getProjectRelativePath( location.getAbsolutePath() );
            }
        }

        return null;
    }

}
