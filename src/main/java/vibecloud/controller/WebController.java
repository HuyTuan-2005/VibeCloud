package vibecloud.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vibecloud.dto.auth.LoginRequest;
import vibecloud.dto.auth.RegisterRequest;
import vibecloud.dto.auth.UpdateProfileRequest;
import vibecloud.dto.file.FileResponse;
import vibecloud.dto.folder.FolderResponse;
import vibecloud.entity.User;
import vibecloud.exception.ResourceConflictException;
import vibecloud.service.FileService;
import vibecloud.service.FolderService;
import vibecloud.service.UserService;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class WebController {

    private static final String CURRENT_USER_ID_SESSION_KEY = "currentUserId";
    private static final String DELETED_STATUS = "DELETED";
    private static final double BYTES_PER_GB = 1024D * 1024D * 1024D;

    private final UserService userService;
    private final FileService fileService;
    private final FolderService folderService;

    @GetMapping("/login")
    public String login(HttpSession session, Model model) {
        if (isAuthenticated(session)) {
            return "redirect:/";
        }

        if (!model.containsAttribute("loginRequest")) {
            model.addAttribute("loginRequest", new LoginRequest("", ""));
        }

        return "login";
    }

    @PostMapping("/login")
    public String login(
            @Valid @ModelAttribute("loginRequest") LoginRequest request,
            BindingResult bindingResult,
            HttpSession session,
            Model model
    ) {
        if (bindingResult.hasErrors()) {
            return "login";
        }

        try {
            var user = userService.authenticate(request);
            authenticateSession(session, user);
            return "redirect:/";
        } catch (IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage());
            return "login";
        }
    }

    @GetMapping("/register")
    public String register(HttpSession session, Model model) {
        if (isAuthenticated(session)) {
            return "redirect:/";
        }

        if (!model.containsAttribute("registerRequest")) {
            model.addAttribute("registerRequest", new RegisterRequest("", "", "", "", ""));
        }

        return "register";
    }

    @PostMapping("/register")
    public String register(
            @Valid @ModelAttribute("registerRequest") RegisterRequest request,
            BindingResult bindingResult,
            HttpSession session,
            Model model
    ) {
        if (bindingResult.hasErrors()) {
            return "register";
        }

        try {
            var user = userService.register(request);
            authenticateSession(session, user);
            return "redirect:/";
        } catch (IllegalArgumentException | ResourceConflictException exception) {
            model.addAttribute("error", exception.getMessage());
            return "register";
        }
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    // Cập nhật lại hàm index trong WebController.java
    @GetMapping("/")
    public String index(
            @RequestParam(required = false) UUID folderId,
            @RequestParam(required = false) String keyword,
            HttpSession session,
            Model model
    ) {
        var currentUser = resolveCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        addCurrentUserAttributes(model, currentUser);

        List<FileResponse> files;
        List<FolderResponse> folders;
        List<FolderResponse> breadcrumbs;

        if (org.springframework.util.StringUtils.hasText(keyword)) {
            files = fileService.searchFiles(currentUser.getId(), keyword)
                    .stream()
                    .filter(file -> !hasDeletedStatus(file.status()))
                    .toList();
            folders = List.of();
            breadcrumbs = List.of();
            model.addAttribute("searchKeyword", keyword);
        }
        else {
            files = fileService.listFiles(currentUser.getId(), folderId)
                    .stream()
                    .filter(file -> !hasDeletedStatus(file.status()))
                    .toList();
            folders = folderService.listFolders(currentUser.getId(), folderId)
                    .stream()
                    .filter(folder -> !hasDeletedStatus(folder))
                    .toList();
            breadcrumbs = folderService.getFolderBreadcrumbs(currentUser.getId(), folderId);
        }

        model.addAttribute("files", files);
        model.addAttribute("folders", folders);
        model.addAttribute("breadcrumbs", breadcrumbs);
        model.addAttribute("currentFolderId", folderId);

        return "index";
    }

    @GetMapping("/trash")
    public String trash(HttpSession session, Model model) {
        var currentUser = resolveCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        addCurrentUserAttributes(model, currentUser);

        var deletedFiles = fileService.listFiles(currentUser.getId(), null)
                .stream()
                .filter(file -> hasDeletedStatus(file.status()))
                .toList();
        var deletedFolders = folderService.listFolders(currentUser.getId(), null)
                .stream()
                .filter(this::hasDeletedStatus)
                .toList();

        model.addAttribute("deletedFiles", deletedFiles);
        model.addAttribute("deletedFolders", deletedFolders);
        model.addAttribute("trashEmpty", deletedFiles.isEmpty() && deletedFolders.isEmpty());
        return "trash";
    }

    @GetMapping({"/profile", "/settings"})
    public String profile(HttpSession session, Model model) {
        var currentUser = resolveCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        addCurrentUserAttributes(model, currentUser);
        if (!model.containsAttribute("profileRequest")) {
            model.addAttribute("profileRequest", UpdateProfileRequest.from(currentUser));
        }

        return "profile";
    }

    @PostMapping("/profile")
    public String updateProfile(
            @Valid @ModelAttribute("profileRequest") UpdateProfileRequest request,
            BindingResult bindingResult,
            HttpSession session,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        var currentUser = resolveCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        addCurrentUserAttributes(model, currentUser);
        if (bindingResult.hasErrors()) {
            return "profile";
        }

        try {
            var updatedUser = userService.updateProfile(currentUser.getId(), request);
            addCurrentUserAttributes(model, updatedUser);
            redirectAttributes.addFlashAttribute("success", "Đã cập nhật thông tin cá nhân");
            return "redirect:/profile";
        } catch (IllegalArgumentException | ResourceConflictException exception) {
            model.addAttribute("error", exception.getMessage());
            return "profile";
        }
    }

    private boolean isAuthenticated(HttpSession session) {
        return resolveCurrentUser(session) != null;
    }

    private User resolveCurrentUser(HttpSession session) {
        var userId = session.getAttribute(CURRENT_USER_ID_SESSION_KEY);
        if (!(userId instanceof UUID currentUserId)) {
            return null;
        }

        try {
            return userService.getById(currentUserId);
        } catch (IllegalArgumentException exception) {
            session.invalidate();
            return null;
        }
    }

    private void authenticateSession(HttpSession session, User user) {
        session.setAttribute(CURRENT_USER_ID_SESSION_KEY, user.getId());
    }


    private long normalizeUsedStorage(User user) {
        var usedStorage = user.getUsedStorage();
        if (usedStorage == null || usedStorage < 0) {
            return 0L;
        }

        return usedStorage;
    }

    private long normalizeMaxStorage(User user) {
        var maxStorage = user.getMaxStorage();
        if (maxStorage == null || maxStorage <= 0) {
            return User.DEFAULT_MAX_STORAGE;
        }

        return maxStorage;
    }
    private void addCurrentUserAttributes(Model model, User user) {
        var displayName = resolveDisplayName(user);
        var usedStorage = normalizeUsedStorage(user);
        var maxStorage = normalizeMaxStorage(user);

        user.setUsedStorage(usedStorage);
        user.setMaxStorage(maxStorage);

        model.addAttribute("user", user);
        model.addAttribute("userId", user.getId());
        model.addAttribute("username", displayName);
        model.addAttribute("accountUsername", user.getUsername());
        model.addAttribute("email", user.getEmail());
        model.addAttribute("initials", resolveInitials(displayName, user.getUsername()));

        // Cập nhật: Trả về chuỗi dung lượng linh hoạt (KB, MB, GB) thay vì fix cứng GB
        model.addAttribute("usedStorageText", formatStorage(usedStorage));
        model.addAttribute("maxStorageText", formatStorage(maxStorage));

        // Tính toán phần trăm (giữ lại số thập phân)
        double percent = calculateStorageUsagePercent(usedStorage, maxStorage);
        model.addAttribute("storageUsagePercent", String.format(Locale.US, "%.1f", percent));
    }

    // Hàm format mới: Tự động tính toán hiển thị B, KB, MB hoặc GB
    private String formatStorage(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double size = bytes / 1024.0;
        int unitIndex = 0;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024.0;
            unitIndex++;
        }
        return String.format(Locale.US, "%.2f %s", size, units[unitIndex]).replace(".00 ", " ");
    }

    // Hàm tính % mới: Cố định hiển thị số thập phân
    private double calculateStorageUsagePercent(long usedStorage, long maxStorage) {
        if (maxStorage <= 0) return 0.0;
        double percent = (usedStorage * 100.0) / maxStorage;
        // Nếu user có dùng dung lượng nhưng nhỏ hơn 0.1%, vẫn hiển thị 0.1% để thanh có màu
        if (usedStorage > 0 && percent < 0.1) {
            return 0.1;
        }
        return Math.min(100.0, percent);
    }




    private String resolveDisplayName(User user) {
        return hasText(user.getFullName()) ? user.getFullName() : user.getUsername();
    }

    private String resolveInitials(String displayName, String username) {
        var nameParts = displayName.trim().split("\\s+");
        if (nameParts.length >= 2) {
            return firstCharacter(nameParts[nameParts.length - 2]) + firstCharacter(nameParts[nameParts.length - 1]);
        }

        var fallback = hasText(username) ? username : displayName;
        return fallback.length() <= 2 ? fallback.toUpperCase() : fallback.substring(0, 2).toUpperCase();
    }

    private String firstCharacter(String value) {
        return value.substring(0, 1).toUpperCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasDeletedStatus(FileResponse file) {
        return file != null && hasDeletedStatus(file.status());
    }

    private boolean hasDeletedStatus(FolderResponse folder) {
        if (folder == null) {
            return false;
        }

        try {
            var statusMethod = folder.getClass().getMethod("status");
            return hasDeletedStatus(statusMethod.invoke(folder));
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private boolean hasDeletedStatus(Object status) {
        if (status == null) {
            return false;
        }

        if (status instanceof Enum<?> enumStatus) {
            return DELETED_STATUS.equals(enumStatus.name());
        }

        return DELETED_STATUS.equals(status.toString());
    }
}
