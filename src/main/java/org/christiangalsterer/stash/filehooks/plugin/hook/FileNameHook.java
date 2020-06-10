package org.christiangalsterer.stash.filehooks.plugin.hook;

import com.atlassian.bitbucket.content.Change;
import com.atlassian.bitbucket.hook.repository.PreRepositoryHook;
import com.atlassian.bitbucket.hook.repository.PreRepositoryHookContext;
import com.atlassian.bitbucket.hook.repository.RepositoryHookRequest;
import com.atlassian.bitbucket.hook.repository.RepositoryHookResult;
import com.atlassian.bitbucket.i18n.I18nService;
import com.atlassian.bitbucket.repository.RefChange;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scope.Scope;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import com.atlassian.bitbucket.setting.SettingsValidator;

import com.google.common.base.Strings;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.counting;
import static java.util.function.Function.identity;

import static org.christiangalsterer.stash.filehooks.plugin.hook.Predicates.*;

/**
 * Checks the name and path of a file in the pre-receive phase and rejects the push when the changeset contains files which match the configured file name pattern.
 */


public class FileNameHook implements PreRepositoryHook<RepositoryHookRequest>, SettingsValidator {
    
    private static final String SETTINGS_INCLUDE_PATTERN = "pattern";
    private static final String SETTINGS_EXCLUDE_PATTERN = "pattern-exclude";
    private static final String SETTINGS_BRANCHES_PATTERN = "pattern-branches";

    private final ChangesetService changesetService;
    private final I18nService i18n;
    
    public FileNameHook(ChangesetService changesetService, I18nService i18n) {
        this.changesetService = changesetService;
        this.i18n = i18n;
    }

    @Nonnull
    @Override
    public RepositoryHookResult preUpdate(  @Nonnull PreRepositoryHookContext context,
                                            @Nonnull RepositoryHookRequest request) {
        Repository repository = request.getRepository();
        FileNameHookSetting setting = getSettings(context.getSettings());
        Optional<Pattern> branchesPattern = setting.getBranchesPattern();

        Collection<RefChange> filteredRefChanges = request.getRefChanges()
                                                            .stream()
                                                            .filter(isNotDeleteRefChange.and(isNotTagRefChange))
                                                            .collect(toList());
        if(branchesPattern.isPresent()) {
            filteredRefChanges = filteredRefChanges.stream()
                                                    .filter(matchesBranchPattern(branchesPattern.get()))
                                                    .collect(toList());
        }

        Iterable<Change> changes = changesetService.getChanges(filteredRefChanges, repository);

        Map<String, Long> deletePathsCount = StreamSupport.stream(changes.spliterator(), false)
                                                            .filter(isDeleteChange)
                                                            .map(Functions.CHANGE_TO_PATH)
                                                            .collect(groupingBy(identity(), counting()));
        
        Map<String, Long> filteredPathsCount = StreamSupport.stream(changes.spliterator(), false)
                                                            .filter(isNotDeleteChange)
                                                            .map(Functions.CHANGE_TO_PATH)
                                                            .collect(groupingBy(identity(), counting()));

        Collection<String> filteredPaths = filteredPathsCount.entrySet()
                                                                .stream()
                                                                .filter(m -> !m.getValue().equals(deletePathsCount.get(m.getKey())))
                                                                .map(Map.Entry::getKey)
                                                                .filter(setting.getIncludePattern().asPredicate())
                                                                .collect(toList());
                                                                
        if(setting.getExcludePattern().isPresent()) {
            Pattern excludePattern = setting.getExcludePattern().get();
            filteredPaths = filteredPaths.stream()
                                         .filter(excludePattern.asPredicate().negate())
                                         .collect(toList());
        }
        
        ArrayList<String> resultList = new ArrayList<>();
        boolean hookPassed = true;

        if(filteredPaths.size() > 0) {
            hookPassed = false;
            for (String path: filteredPaths) {
                String msg;
                if(branchesPattern.isPresent()) {
                    msg = String.format("File [%s] violates file name pattern [%s] for branch [%s].", path, setting.getIncludePattern().pattern(), branchesPattern.get());
                } else {
                    msg = String.format("File [%s] violates file name pattern [%s].", path, setting.getIncludePattern().pattern());
                }
                resultList.add(msg);
            }
        }
        if(hookPassed){
            return RepositoryHookResult.accepted();
        } else {
            return RepositoryHookResult.rejected("File does not match name pattern", Arrays.toString(resultList.toArray()));
        }
    
    }

    private FileNameHookSetting getSettings(Settings settings) {
        String includeRegex = settings.getString(SETTINGS_INCLUDE_PATTERN);
        String excludeRegex = settings.getString(SETTINGS_EXCLUDE_PATTERN);
        String branchesRegex = settings.getString(SETTINGS_BRANCHES_PATTERN);

        return new FileNameHookSetting(includeRegex, excludeRegex, branchesRegex);
    }

    @Override
    public void validate(Settings settings, SettingsValidationErrors errors, Scope scope) {

        if(Strings.isNullOrEmpty(settings.getString(SETTINGS_INCLUDE_PATTERN))) {
            errors.addFieldError(SETTINGS_INCLUDE_PATTERN, i18n.getText("filename-hook.error.pattern", "Pattern is not a valid regular expression"));
        } else {
            try {
                Pattern.compile(settings.getString(SETTINGS_INCLUDE_PATTERN, ""));
            } catch(PatternSyntaxException e){
                errors.addFieldError(SETTINGS_INCLUDE_PATTERN, i18n.getText("filename-hook.error.pattern", "Pattern is not a valid regular expression"));
            }
        }

        if(!Strings.isNullOrEmpty(settings.getString(SETTINGS_EXCLUDE_PATTERN))) {
            try {
                Pattern.compile(settings.getString(SETTINGS_EXCLUDE_PATTERN));
            } catch(PatternSyntaxException e){
                errors.addFieldError(SETTINGS_EXCLUDE_PATTERN, i18n.getText("filename-hook.error.pattern", "Pattern is not a valid regular expression"));
            }
        }

        if(!Strings.isNullOrEmpty(settings.getString(SETTINGS_BRANCHES_PATTERN))) {
            try {
                Pattern.compile(settings.getString(SETTINGS_BRANCHES_PATTERN));
            } catch(PatternSyntaxException e){
                errors.addFieldError(SETTINGS_BRANCHES_PATTERN, i18n.getText("filename-hook.error.pattern", "Pattern is not a valid regular expression"));
            }
        }
    }



}
