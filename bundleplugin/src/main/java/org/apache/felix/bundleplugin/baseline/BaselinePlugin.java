/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.bundleplugin.baseline;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.util.xml.PrettyPrintXMLWriter;
import org.codehaus.plexus.util.xml.XMLWriter;

import aQute.bnd.version.Version;

/**
 * BND Baseline check between two bundles.
 * @since 2.4.1
 */
@Mojo( name = "baseline", threadSafe = true, requiresDependencyResolution = ResolutionScope.TEST )
@Execute( phase = LifecyclePhase.VERIFY )
public final class BaselinePlugin
    extends AbstractBaselinePlugin
{

    private static final String TABLE_PATTERN = "%s %-50s %-10s %-10s %-10s %-10s %-10s";

    /**
     * An XML output file to render to <code>${project.build.directory}/baseline.xml</code>.
     */
    @Parameter(defaultValue="${project.build.directory}/baseline.xml")
    private File xmlOutputFile;

    /**
     * Whether to log the results to the console or not, true by default.
     */
    @Parameter(defaultValue="true", property="logResults" )
    private boolean logResults;

    private static final class Context {
        public FileWriter writer;
        public XMLWriter xmlWriter;
    }

    @Override
    protected Object init(final Object noContext)
    {
        if ( xmlOutputFile != null )
        {
            xmlOutputFile.getParentFile().mkdirs();
            try
            {
                final Context ctx = new Context();
                ctx.writer = new FileWriter( xmlOutputFile );
                ctx.xmlWriter = new PrettyPrintXMLWriter( ctx.writer );
                return ctx;
            }
            catch ( IOException e )
            {
                getLog().warn( "No XML report will be produced, cannot write data to " + xmlOutputFile, e );
            }
        }
        return null;
    }

    @Override
    protected void close(final Object writer)
    {
        if ( writer != null )
        {
            try {

                ((Context)writer).writer.close();
            }
            catch (IOException e)
            {
                // ignore
            }
        }
    }
    @Override
    protected void startBaseline( Object context,
                                  String generationDate,
                                  String bundleName,
                                  String currentVersion,
                                  String previousVersion )
    {
        final XMLWriter xmlWriter = context == null ? null : ((Context)context).xmlWriter;
        if ( isLoggingResults() )
        {
            log( "Baseline Report - Generated by Apache Felix Maven Bundle Plugin on %s based on Bnd - see http://www.aqute.biz/Bnd/Bnd",
                 generationDate );
            log( "Comparing bundle %s version %s to version %s", bundleName, currentVersion, previousVersion );
            log( "" );
            log( TABLE_PATTERN,
                 " ",
                 "PACKAGE_NAME",
                 "DELTA",
                 "CUR_VER",
                 "BASE_VER",
                 "REC_VER",
                 "WARNINGS",
                 "ATTRIBUTES" );
            log( TABLE_PATTERN,
                 "=",
                 "==================================================",
                 "==========",
                 "==========",
                 "==========",
                 "==========",
                 "==========",
                 "==========" );
        }

        if ( xmlWriter != null )
        {
            xmlWriter.startElement( "baseline" );
            xmlWriter.addAttribute( "version", "1.0.0" );
            xmlWriter.addAttribute( "vendor", "The Apache Software Foundation" );
            xmlWriter.addAttribute( "vendorURL", "http://www.apache.org/" );
            xmlWriter.addAttribute( "generator", "Apache Felix Maven Bundle Plugin" );
            xmlWriter.addAttribute( "generatorURL", "http://felix.apache.org/site/apache-felix-maven-bundle-plugin-bnd.html" );
            xmlWriter.addAttribute( "analyzer", "Bnd" );
            xmlWriter.addAttribute( "analyzerURL", "http://www.aqute.biz/Bnd/Bnd" );
            xmlWriter.addAttribute( "generatedOn", generationDate );
            xmlWriter.addAttribute( "bundleName", bundleName );
            xmlWriter.addAttribute( "currentVersion", currentVersion );
            xmlWriter.addAttribute( "previousVersion", previousVersion );
        }
    }

    @Override
    protected void startPackage( Object context,
                                 boolean mismatch,
                                 String name,
                                 String shortDelta,
                                 String delta,
                                 Version newerVersion,
                                 Version olderVersion,
                                 Version suggestedVersion,
                                 DiffMessage diffMessage,
                                 Map<String,String> attributes )
    {
        final XMLWriter xmlWriter = context == null ? null : ((Context)context).xmlWriter;
        if ( isLoggingResults() )
        {
            log( TABLE_PATTERN,
                 mismatch ? '*' : shortDelta,
                 name,
                 delta,
                 newerVersion,
                 olderVersion,
                 suggestedVersion,
                 diffMessage != null ? diffMessage : '-',
                 attributes );
        }

        if ( xmlWriter != null )
        {
            xmlWriter.startElement( "package" );
            xmlWriter.addAttribute( "name", name );
            xmlWriter.addAttribute( "delta", delta );
            simpleElement( xmlWriter, "mismatch", String.valueOf( mismatch ) );
            simpleElement( xmlWriter, "newerVersion", newerVersion.toString() );
            simpleElement( xmlWriter, "olderVersion", olderVersion.toString() );
            if ( suggestedVersion != null )
            {
                simpleElement( xmlWriter, "suggestedVersion", suggestedVersion.toString() );
            }

            if ( diffMessage != null )
            {
                simpleElement( xmlWriter, diffMessage.getType().name(), diffMessage.getMessage() );
            }

            xmlWriter.startElement( "attributes" );
            if (attributes != null)
            {
                for (Entry<String, String> attribute : attributes.entrySet())
                {
                    String attributeName = attribute.getKey();
                    if (':' == attributeName.charAt(attributeName.length() - 1))
                    {
                        attributeName = attributeName.substring(0, attributeName.length() - 1);
                    }
                    String attributeValue = attribute.getValue();

                    xmlWriter.startElement(attributeName);
                    xmlWriter.writeText(attributeValue);
                    xmlWriter.endElement();
                }
            }
            xmlWriter.endElement();
        }
    }

    @Override
    protected void startDiff( Object context, int depth, String type, String name, String delta, String shortDelta )
    {
        final XMLWriter xmlWriter = context == null ? null : ((Context)context).xmlWriter;
        if ( isLoggingResults() )
        {
            log( "%-" + (depth * 4) + "s %s %s %s",
                 "",
                 shortDelta,
                 type,
                 name );
        }

        if ( xmlWriter != null )
        {
            xmlWriter.startElement( type );
            xmlWriter.addAttribute( "name", name );
            xmlWriter.addAttribute( "delta", delta );
        }
    }

    @Override
    protected void endDiff( Object context, int depth )
    {
        final XMLWriter xmlWriter = context == null ? null : ((Context)context).xmlWriter;
        if ( xmlWriter != null )
        {
            xmlWriter.endElement();
        }
    }

    @Override
    protected void endPackage(Object context)
    {
        final XMLWriter xmlWriter = context == null ? null : ((Context)context).xmlWriter;
        if ( isLoggingResults() )
        {
            log( "-----------------------------------------------------------------------------------------------------------" );
        }

        if ( xmlWriter != null )
        {
            xmlWriter.endElement();
        }
    }

    @Override
    protected void endBaseline(Object context)
    {
        final XMLWriter xmlWriter = context == null ? null : ((Context)context).xmlWriter;
        if ( xmlWriter != null )
        {
            xmlWriter.endElement();
        }
    }

    private boolean isLoggingResults()
    {
        return logResults && getLog().isInfoEnabled();
    }

    private void log( String format, Object...args )
    {
        getLog().info( String.format( format, args ) );
    }

    private void simpleElement( XMLWriter xmlWriter, String name, String value )
    {
        xmlWriter.startElement( name );
        xmlWriter.writeText( value );
        xmlWriter.endElement();
    }
}
