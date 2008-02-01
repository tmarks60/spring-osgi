/*
 * Copyright 2006-2008 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.osgi.iandt.io;

import java.io.IOException;
import java.util.jar.Manifest;

import org.osgi.framework.Constants;
import org.springframework.core.io.Resource;
import org.springframework.util.ObjectUtils;

/**
 * Test wildcard matching on bundles with a defined bundle classpath. This is
 * one of the heaviest IO tests as it involves both a bundle classpath and
 * fragments.
 * 
 * @author Costin Leau
 * 
 */
public class BundleClassPathWildcardTest extends BaseIoTest {

	protected Manifest getManifest() {
		Manifest mf = super.getManifest();
		// add bundle classpath
		mf.getMainAttributes().putValue(Constants.BUNDLE_CLASSPATH,
			".,bundleclasspath/folder,bundleclasspath/simple.jar");
		return mf;
	}

	protected String[] getBundleContentPattern() {
		return (String[]) ObjectUtils.addObjectToArray(super.getBundleContentPattern(), "bundleclasspath/**/*");
	}

	public void testClassPathFilesOnBundleClassPath() throws Exception {
		// use org to make sure the bundle class is properly considered (especially for folder based classpath)
		Resource[] res = patternLoader.getResources("classpath:org/**/*.file");
		System.out.println("array count is " + res.length);
		System.out.println(ObjectUtils.nullSafeToString(res));
		assertTrue("bundle classpath jar not considered", containsString(res, "jar-folder.file"));
	}

	public void testAllClassPathFilesOnBundleClassPath() throws Exception {
		// use org to make sure the bundle class is properly considered (especially for folder based classpath)
		Resource[] res = patternLoader.getResources("classpath*:org/**/*.file");
		System.out.println("array count is " + res.length);
		System.out.println(ObjectUtils.nullSafeToString(res));
		assertTrue("bundle classpath jar not considered", containsString(res, "jar-folder.file"));
	}

	public void testRootFileOnBundleClassPath() throws Exception {
		// use org to make sure the bundle class is properly considered (especially for folder based classpath)
		Resource[] res = patternLoader.getResources("classpath:*.file");
		System.out.println("array count is " + res.length);

		System.out.println(ObjectUtils.nullSafeToString(res));
		assertTrue("bundle classpath jar not considered", containsString(res, "jar.file"));
	}

	public void testRootFileOnAllBundleClassPath() throws Exception {
		// use org to make sure the bundle class is properly considered (especially for folder based classpath)
		Resource[] res = patternLoader.getResources("classpath:*.file");
		System.out.println("array count is " + res.length);

		System.out.println(ObjectUtils.nullSafeToString(res));
		assertTrue("bundle classpath jar not considered", containsString(res, "jar.file"));
	}

	private boolean containsString(Resource[] array, String str) throws IOException {
		for (int i = 0; i < array.length; i++) {
			Resource resource = array[i];
			if (resource.getURL().toExternalForm().indexOf(str) > -1)
				return true;
		}
		return false;
	}

	protected boolean isDisabledInThisEnvironment(String testMethodName) {
		return isKF();
	}
	
	
}
