package com.app.common.services;

import com.app.common.events.FileBookmarkToggledEvent;
import com.app.common.dtos.BookmarkChange;
import com.app.common.models.FileBookmark;
import com.app.common.models.User;
import com.app.common.modules.session.Session;
import com.app.common.repositories.FileBookmarkRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class FileBookmarkService {

    private final FileBookmarkRepository fileBookmarkRepository;
    private final Session session;
    private final ApplicationEventPublisher eventPublisher;

    public FileBookmarkService(FileBookmarkRepository fileBookmarkRepository, Session session,
            ApplicationEventPublisher eventPublisher) {
        this.fileBookmarkRepository = fileBookmarkRepository;
        this.session = session;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Sets bookmark state for the current user and given file.
     * Idempotent — calling with true multiple times stays bookmarked.
     *
     * @return BookmarkChange containing the result state and timestamp
     */
    @Transactional
    public BookmarkChange setBookmarked(Long fileId, boolean bookmarked) {
        Long userId = currentUserId();
        if (bookmarked) {
            FileBookmark bookmark = fileBookmarkRepository.insertIfAbsent(userId, fileId);
            return doBookmarkChange(userId, fileId, true, bookmark.getUpdatedAt());
        }
        fileBookmarkRepository.unbookmark(userId, fileId);
        return doBookmarkChange(userId, fileId, false, null);
    }

    private BookmarkChange doBookmarkChange(Long userId, Long fileId, boolean bookmarked, String updatedAt) {
        eventPublisher.publishEvent(
                new FileBookmarkToggledEvent(this, userId, fileId, bookmarked, updatedAt));
        return new BookmarkChange(userId, fileId, bookmarked, updatedAt);
    }

    public boolean isBookmarked(Long fileId) {
        return fileBookmarkRepository.isBookmarked(currentUserId(), fileId);
    }

    public Optional<String> getBookmarkTimestamp(Long fileId) {
        return fileBookmarkRepository.findBookmarkTimestamp(currentUserId(), fileId);
    }

    @Transactional
    public void bookmarkAll(List<Long> fileIds) {
        Long userId = currentUserId();
        Set<Long> existingIds = fileBookmarkRepository.filterBookmarkedIds(userId, fileIds);
        List<Long> toBookmark = fileIds.stream()
                .filter(fid -> !existingIds.contains(fid))
                .toList();
        if (!toBookmark.isEmpty()) {
            fileBookmarkRepository.bookmarkAll(userId, toBookmark);
            eventPublisher.publishEvent(new FileBookmarkToggledEvent(this, userId, toBookmark, true));
        }
    }

    @Transactional
    public void unbookmarkAll(List<Long> fileIds) {
        Long userId = currentUserId();
        Set<Long> existingIds = fileBookmarkRepository.filterBookmarkedIds(userId, fileIds);
        List<Long> toUnbookmark = fileIds.stream()
                .filter(existingIds::contains)
                .toList();
        if (!toUnbookmark.isEmpty()) {
            fileBookmarkRepository.unbookmarkAll(userId, toUnbookmark);
            eventPublisher.publishEvent(new FileBookmarkToggledEvent(this, userId, toUnbookmark, false));
        }
    }

    private Long currentUserId() {
        User user = session.getUser();
        if (user == null) {
            throw new IllegalStateException("No authenticated user");
        }
        return user.getId();
    }
}
