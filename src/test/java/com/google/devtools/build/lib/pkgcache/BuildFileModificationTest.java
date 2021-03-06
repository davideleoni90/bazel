// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.pkgcache;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.skyframe.DiffAwareness;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PackageLookupValue.BuildFileName;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SequencedSkyframeExecutor;
import com.google.devtools.build.lib.skyframe.SkyValueDirtinessChecker;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.syntax.SkylarkSemanticsOptions;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.OptionsParser;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for package loading.
 */
@RunWith(JUnit4.class)
public class BuildFileModificationTest extends FoundationTestCase {

  private ManualClock clock = new ManualClock();
  private AnalysisMock analysisMock;
  private ConfiguredRuleClassProvider ruleClassProvider;
  private SkyframeExecutor skyframeExecutor;

  @Before
  public final void disableLogging() throws Exception {
    Logger.getLogger("com.google.devtools").setLevel(Level.SEVERE);
  }

  @Before
  public final void initializeSkyframeExecutor() throws Exception {
    analysisMock = AnalysisMock.get();
    ruleClassProvider = analysisMock.createRuleClassProvider();
    BlazeDirectories directories =
        new BlazeDirectories(outputBase, outputBase, rootDirectory, analysisMock.getProductName());
    skyframeExecutor =
        SequencedSkyframeExecutor.create(
            analysisMock
                .getPackageFactoryForTesting()
                .create(ruleClassProvider, scratch.getFileSystem()),
            directories,
            null, /* BinTools */
            null, /* workspaceStatusActionFactory */
            ruleClassProvider.getBuildInfoFactories(),
            ImmutableList.<DiffAwareness.Factory>of(),
            Predicates.<PathFragment>alwaysFalse(),
            AnalysisMock.get().getSkyFunctions(),
            ImmutableList.<PrecomputedValue.Injected>of(),
            ImmutableList.<SkyValueDirtinessChecker>of(),
            analysisMock.getProductName(),
            CrossRepositoryLabelViolationStrategy.ERROR,
            ImmutableList.of(BuildFileName.BUILD_DOT_BAZEL, BuildFileName.BUILD));
    OptionsParser parser = OptionsParser.newOptionsParser(
        PackageCacheOptions.class, SkylarkSemanticsOptions.class);
    analysisMock.getInvocationPolicyEnforcer().enforce(parser);
    setUpSkyframe(
        parser.getOptions(PackageCacheOptions.class),
        parser.getOptions(SkylarkSemanticsOptions.class));
  }

  private void setUpSkyframe(
      PackageCacheOptions packageCacheOptions,
      SkylarkSemanticsOptions skylarkSemanticsOptions) {
    PathPackageLocator pkgLocator = PathPackageLocator.create(
        null, packageCacheOptions.packagePath, reporter, rootDirectory, rootDirectory);
    packageCacheOptions.showLoadingProgress = true;
    packageCacheOptions.globbingThreads = 7;
    skyframeExecutor.preparePackageLoading(
        pkgLocator,
        packageCacheOptions,
        skylarkSemanticsOptions,
        analysisMock.getDefaultsPackageContent(),
        UUID.randomUUID(),
        ImmutableMap.<String, String>of(),
        ImmutableMap.<String, String>of(),
        new TimestampGranularityMonitor(clock));
    skyframeExecutor.setDeletedPackages(
        ImmutableSet.copyOf(packageCacheOptions.getDeletedPackages()));
  }

  @Override
  protected FileSystem createFileSystem() {
    return new InMemoryFileSystem(clock);
  }

  private void invalidatePackages() throws InterruptedException {
    skyframeExecutor.invalidateFilesUnderPathForTesting(
        reporter, ModifiedFileSet.EVERYTHING_MODIFIED, rootDirectory);
  }

  private Package getPackage(String packageName)
      throws NoSuchPackageException, InterruptedException {
    return skyframeExecutor.getPackageManager().getPackage(reporter,
        PackageIdentifier.createInMainRepo(packageName));
  }

  @Test
  public void testCTimeChangeDetectedWithError() throws Exception {
    reporter.removeHandler(failFastHandler);
    Path build = scratch.file(
        "a/BUILD", "cc_library(name='a', feet='stinky')".getBytes(StandardCharsets.ISO_8859_1));
    Package a1 = getPackage("a");
    assertTrue(a1.containsErrors());
    assertContainsEvent("//a:a: no such attribute 'feet'");
    eventCollector.clear();
    // writeContent updates mtime and ctime. Note that we keep the content length exactly the same.
    clock.advanceMillis(1);
    FileSystemUtils.writeContent(
        build, "cc_library(name='a', srcs=['a.cc'])".getBytes(StandardCharsets.ISO_8859_1));

    invalidatePackages();
    Package a2 = getPackage("a");
    assertNotSame(a1, a2);
    assertFalse(a2.containsErrors());
    assertNoEvents();
  }

  @Test
  public void testCTimeChangeDetected() throws Exception {
    Path path = scratch.file(
        "pkg/BUILD", "cc_library(name = 'foo')\n".getBytes(StandardCharsets.ISO_8859_1));
    Package oldPkg = getPackage("pkg");

    // Note that the content has exactly the same length as before.
    clock.advanceMillis(1);
    FileSystemUtils.writeContent(
        path, "cc_library(name = 'bar')\n".getBytes(StandardCharsets.ISO_8859_1));
    assertSame(oldPkg, getPackage("pkg")); // Change only becomes visible after invalidatePackages.

    invalidatePackages();

    Package newPkg = getPackage("pkg");
    assertNotSame(oldPkg, newPkg);
    assertNotNull(newPkg.getTarget("bar"));
  }

  @Test
  public void testLengthChangeDetected() throws Exception {
    reporter.removeHandler(failFastHandler);
    Path build = scratch.file(
        "a/BUILD", "cc_library(name='a', srcs=['a.cc'])".getBytes(StandardCharsets.ISO_8859_1));
    Package a1 = getPackage("a");
    eventCollector.clear();
    // Note that we didn't advance the clock, so ctime/mtime is the same as before.
    // However, the file contents are one byte longer.
    FileSystemUtils.writeContent(
        build, "cc_library(name='ab', srcs=['a.cc'])".getBytes(StandardCharsets.ISO_8859_1));

    invalidatePackages();
    Package a2 = getPackage("a");
    assertNotSame(a1, a2);
    assertNoEvents();
  }

  @Test
  public void testTouchedBuildFileCausesReloadAfterSync() throws Exception {
    Path path = scratch.file("pkg/BUILD",
                             "cc_library(name = 'foo')");

    Package oldPkg = getPackage("pkg");
    // Change ctime to 1.
    clock.advanceMillis(1);
    path.setLastModifiedTime(1001);
    assertSame(oldPkg, getPackage("pkg")); // change not yet visible

    invalidatePackages();

    Package newPkg = getPackage("pkg");
    assertNotSame(oldPkg, newPkg);
  }
}
