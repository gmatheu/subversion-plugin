/*
 * The MIT License
 *
 * Copyright (c) 2010-2011, Manufacture Francaise des Pneumatiques Michelin,
 * Romain Seguy, Jeff Blaisdell
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.scm.listtagsparameter;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.scm.CredentialsSVNAuthenticationProviderImpl;
import hudson.scm.SubversionSCM;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jvnet.localizer.ResourceBundleHolder;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationProvider;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.SVNLogClient;
import org.tmatesoft.svn.core.wc.SVNRevision;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Defines a new {@link ParameterDefinition} to be displayed at the top of the
 * configuration page of {@link Job}s.
 * 
 * <p>When used, this parameter will request the user to select a Subversion tag
 * at build-time by displaying a drop-down list. See
 * {@link ListSubversionTagsParameterValue}.</p>
 * 
 * @author Romain Seguy (http://openromain.blogspot.com)
 */
@SuppressWarnings("rawtypes")
public class ListSubversionTagsParameterDefinition extends ParameterDefinition {

    private static final long serialVersionUID = 1L;
/**
   * The Subversion repository which contains the tags to be listed.
   */
  private final String tagsDir;
  private final String credentialsId;
  private final String tagsFilter;
  private final boolean reverseByDate;
  private final boolean reverseByName;
  private final String defaultValue;
  private final String maxTags;
  private static final String SVN_BRANCHES = "branches";
  private static final String SVN_TAGS = "tags";
  private static final String SVN_TRUNK = "trunk";
  //  private static final String SVN_RELEASES = "releases";
  private static List<String> folders = Arrays.asList(SVN_BRANCHES, "releases", SVN_TAGS);

  @Deprecated
  public ListSubversionTagsParameterDefinition(String name, String tagsDir, String tagsFilter, String defaultValue, String maxTags, boolean reverseByDate, boolean reverseByName, String uuid) {
    this(name, tagsDir, null, tagsFilter, defaultValue, maxTags, reverseByDate, reverseByName);
  }

  @Deprecated
  public ListSubversionTagsParameterDefinition(String name, String tagsDir, String tagsFilter, String defaultValue, String maxTags, boolean reverseByDate, boolean reverseByName, String uuid, String credentialsId) {
      this(name, tagsDir, credentialsId, tagsFilter, defaultValue, maxTags, reverseByDate, reverseByName);
  }

  @DataBoundConstructor
  public ListSubversionTagsParameterDefinition(String name, String tagsDir, String credentialsId, String tagsFilter, String defaultValue, String maxTags, boolean reverseByDate, boolean reverseByName) {
    super(name, ResourceBundleHolder.get(ListSubversionTagsParameterDefinition.class).format("TagDescription"));
    this.tagsDir = Util.removeTrailingSlash(tagsDir);
    this.tagsFilter = tagsFilter;
    this.reverseByDate = reverseByDate;
    this.reverseByName = reverseByName;
    this.defaultValue = defaultValue;
    this.maxTags = maxTags;
    this.credentialsId = credentialsId;
  }

  // This method is invoked from a GET or POST HTTP request
  @Override
  public ParameterValue createValue(StaplerRequest req) {
    String[] values = req.getParameterValues(getName());
    if(values == null || values.length != 1) {
        return this.getDefaultParameterValue(); 
    }
    else {
      return new ListSubversionTagsParameterValue(getName(), getTagsDir(), values[0]);
    }
  }

  // This method is invoked when the user clicks on the "Build" button of Hudon's GUI
  @Override
  public ParameterValue createValue(StaplerRequest req, JSONObject formData) {
    ListSubversionTagsParameterValue value = req.bindJSON(ListSubversionTagsParameterValue.class, formData);
    value.setTagsDir(getTagsDir());
    // here, we could have checked for the value of the "tag" attribute of the
    // parameter value, but it's of no use because if we return null the build
    // still goes on...
    return value;
  }
  
  @Override
  public ParameterValue getDefaultParameterValue() {
    if (StringUtils.isEmpty(this.defaultValue)) {
      return null;
    }
    return new ListSubversionTagsParameterValue(getName(), getTagsDir(), this.defaultValue);
  }

  @Override
  public DescriptorImpl getDescriptor() {
    return (DescriptorImpl) super.getDescriptor();
  }

  /**
   * Returns a list of Subversion dirs to be displayed in
   * {@code ListSubversionTagsParameterDefinition/index.jelly}.
   *
   * <p>This method plainly reuses settings that must have been previously
   * defined when configuring the Subversion SCM.</p>
   *
   * <p>This method never returns {@code null}. In case an error happens, the
   * returned list contains an error message prefixed by {@code !}.</p>
   */
  @Nonnull public List<String> getTags(@Nullable Job context) {
    List<String> dirs = new ArrayList<String>();

    try {
      ISVNAuthenticationProvider authProvider = CredentialsSVNAuthenticationProviderImpl.createAuthenticationProvider(
              context, getTagsDir(), getCredentialsId(), null
      );
      ISVNAuthenticationManager authManager = SubversionSCM.createSvnAuthenticationManager(authProvider);
      SVNURL repoURL = SVNURL.parseURIDecoded(getTagsDir());

      SVNRepository repo = SVNRepositoryFactory.create(repoURL);
      repo.setAuthenticationManager(authManager);
      SVNLogClient logClient = new SVNLogClient(authManager, null);
      
      if (isSVNRepositoryProjectRoot(repo)) {
        dirs = this.getSVNRootRepoDirectories(logClient, repoURL);
      } else {
        SimpleSVNDirEntryHandler dirEntryHandler = new SimpleSVNDirEntryHandler(tagsFilter);
        logClient.doList(repoURL, SVNRevision.HEAD, SVNRevision.HEAD, false, SVNDepth.IMMEDIATES, SVNDirEntry.DIRENT_TIME, dirEntryHandler);
        dirs = dirEntryHandler.getDirs(isReverseByDate(), isReverseByName());
      }
    }
    catch(SVNException e) {
      // logs are not translated (IMO, this is a bad idea to translate logs)
      LOGGER.log(Level.SEVERE, "An SVN exception occurred while listing the directory entries at " + getTagsDir(), e);
      return Collections.singletonList("!" + ResourceBundleHolder.get(ListSubversionTagsParameterDefinition.class).format("SVNException"));
    }

    // SVNKit's doList() method returns also the parent dir, so we need to remove it
    if(dirs != null) {
      removeParentDir(dirs);
    }
    else {
      LOGGER.log(Level.INFO, "No directory entries were found for the following SVN repository: {0}", getTagsDir());
      return Collections.singletonList("!" + ResourceBundleHolder.get(ListSubversionTagsParameterDefinition.class).format("NoDirectoryEntriesFound"));
    }
    
    // Conform list to the maxTags option.
    Integer max = (isInt(this.maxTags) ? Integer.parseInt(this.maxTags) : null);
    if((max != null) && (dirs.size() > max)) {
      dirs = dirs.subList(0, max);
    }    

    return dirs;
  }

  public String getTagsDir() {
    return tagsDir;
  }

  public String getCredentialsId() {
    return credentialsId;
  }

  public String getTagsFilter() {
    return tagsFilter;
  }

  public boolean isReverseByDate() {
    return reverseByDate;
  }

  public boolean isReverseByName() {
    return reverseByName;
  }
  
  public String getDefaultValue() {
    return defaultValue;
  }

  public String getMaxTags() {
    return maxTags;
  }  

  /**
   * Checks to see if given repository contains a trunk, branches, and tags
   * directories.
   * 
   * @param repo Repository to check.
   * @return True if trunk, branches, and tags exist.
   */
  private boolean isSVNRepositoryProjectRoot(SVNRepository repo) {
    try {
      if (repo.info(SVN_TRUNK, SVNRevision.HEAD.getNumber()) != null
              && repo.info(SVN_BRANCHES, SVNRevision.HEAD.getNumber()) != null
              && repo.info(SVN_TAGS, SVNRevision.HEAD.getNumber()) != null) {
        return true;
      }
    } catch (SVNException e) {
      return false;
    }
    return false;
  }

  /**
   * Appends the target directory to all entries in a list. I.E. 1.2 -->
   * branches/1.2
   * 
   * @param targetDir The target directory to append.
   * @param dirs List of directory entries
   */
  private void appendTargetDir(String targetDir, List<String> dirs) {
    if ((targetDir != null) && (dirs != null) && (dirs.size() > 0)) {
      for (int i = 0; i < dirs.size(); i++) {
        dirs.set(i, targetDir + '/' + dirs.get(i));
      }
    }
  }
  
  private boolean isInt(String value) {
    boolean isInteger = false;
    try {
      Integer.parseInt(value);
      isInteger = true;
    } catch (NumberFormatException e) {
      isInteger = false;
    }
    return isInteger;
  }  

  /**
   * Returns a list of contents from the trunk, branches, and tags
   * directories.
   * 
   * @param logClient
   * @param repoURL
   * @return List of directories.
   * @throws SVNException
   */
  private List<String> getSVNRootRepoDirectories(SVNLogClient logClient, SVNURL repoURL) throws SVNException {
    // Merge trunk with the contents of branches and tags.
    List<String> dirs = new ArrayList<String>();
    dirs.add(SVN_TRUNK);
    for(String folder : folders) {
      List<String> items = fetchItemsFromFolder(logClient, repoURL, folder);
      dirs.addAll(items);
    }


    // Filter out any unwanted repository locations.
    if (StringUtils.isNotBlank(tagsFilter) && dirs.size() > 0) {
      Pattern filterPattern = Pattern.compile(tagsFilter);

        List<String> temp = new ArrayList<String>();
        for (String dir : dirs) {
          if (filterPattern.matcher(dir).matches()) {
            temp.add(dir);
          }
        }
        dirs = temp;
    }

    return dirs;
  }

  private List<String> fetchItemsFromFolder(SVNLogClient logClient, SVNURL repoURL, String folder) throws SVNException {
    SimpleSVNDirEntryHandler entryHandler = new SimpleSVNDirEntryHandler(null);
    SVNURL folderRepo = repoURL.appendPath(folder, true);
    logClient.doList(folderRepo, SVNRevision.HEAD, SVNRevision.HEAD, false, SVNDepth.IMMEDIATES, SVNDirEntry.DIRENT_ALL, entryHandler);
    List<String> items = entryHandler.getDirs(isReverseByDate(), isReverseByName());
    items.remove("");
    appendTargetDir(folder, items);
    return items;
  }

  /**
   * Removes the parent directory (that is, the tags directory) from a list of
   * directories.
   */
  protected void removeParentDir(List<String> dirs) {
    List<String> dirsToRemove = new ArrayList<String>();
    for(String dir : dirs) {
      if(getTagsDir().endsWith(dir)) {
        dirsToRemove.add(dir);
      }
    }
    dirs.removeAll(dirsToRemove);
  }

  @Extension
  public static class DescriptorImpl extends ParameterDescriptor {

    // we reuse as much as possible settings defined at the SCM level
    private SubversionSCM.DescriptorImpl scmDescriptor;

    @Deprecated
    public ISVNAuthenticationProvider createAuthenticationProvider(AbstractProject context) {
      return getSubversionSCMDescriptor().createAuthenticationProvider(context);
    }

    @CheckForNull
    public FormValidation doCheckTagsDir(StaplerRequest req, @AncestorInPath Item context, @QueryParameter String value) {
        Jenkins instance = Jenkins.getInstance();
        if (instance != null) {
            SubversionSCM.ModuleLocation.DescriptorImpl desc = instance.getDescriptorByType(SubversionSCM.ModuleLocation.DescriptorImpl.class);
            if (desc != null) {
                return desc.doCheckRemote(req, context, value);
            }
        }
        return FormValidation.warning("Unable to check tags directory.");
    }

    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item context, @QueryParameter String tagsDir) {
      if (context == null || !context.hasPermission(Item.BUILD)) {
        return new StandardListBoxModel();
      }
      return Jenkins.getInstance().getDescriptorByType(
              SubversionSCM.ModuleLocation.DescriptorImpl.class).fillCredentialsIdItems(context, tagsDir);
    }

    public FormValidation doCheckCredentialsId(StaplerRequest req, @AncestorInPath Item context, @QueryParameter String tagsDir, @QueryParameter String value) {
      if (context == null || !context.hasPermission(Item.BUILD)) {
        return FormValidation.ok();
      }
      return Jenkins.getInstance().getDescriptorByType(
              SubversionSCM.ModuleLocation.DescriptorImpl.class).checkCredentialsId(req, context, tagsDir, value);
    }

    public FormValidation doCheckTagsFilter(@QueryParameter String value) {
      if(value != null && value.length() > 0) {
        try {
          Pattern.compile(value);
        }
        catch(PatternSyntaxException pse) {
          return FormValidation.error(ResourceBundleHolder.get(ListSubversionTagsParameterDefinition.class).format("NotValidRegex"));
        }
      }
      return FormValidation.ok();
    }

    public ListBoxModel doFillTagItems(@AncestorInPath Job<?,?> context, @QueryParameter String param) {
        ListBoxModel model = new ListBoxModel();
        if (context != null) {
            ParametersDefinitionProperty prop = context.getProperty(ParametersDefinitionProperty.class);
            if (prop != null) {
                ParameterDefinition def = prop.getParameterDefinition(param);
                if (def instanceof ListSubversionTagsParameterDefinition) {
                    for (String tag : ((ListSubversionTagsParameterDefinition) def).getTags(context)) {
                        if (tag.startsWith("!")) {
                            model.add(tag.substring(1), "");
                        } else {
                            model.add(tag);
                        }
                    }
                }
            }
        }
        return model;
    }

    @Override
    public String getDisplayName() {
      return ResourceBundleHolder.get(ListSubversionTagsParameterDefinition.class).format("DisplayName");
    }

    /**
     * Returns the descriptor of {@link SubversionSCM}.
     */
    public SubversionSCM.DescriptorImpl getSubversionSCMDescriptor() {
      if(scmDescriptor == null) {
        scmDescriptor = (SubversionSCM.DescriptorImpl) Hudson.getInstance().getDescriptor(SubversionSCM.class);
      }
      return scmDescriptor;
    }

  }

  private final static Logger LOGGER = Logger.getLogger(ListSubversionTagsParameterDefinition.class.getName());

}
