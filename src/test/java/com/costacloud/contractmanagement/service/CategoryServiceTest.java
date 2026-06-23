package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.dto.CategoryRequest;
import com.costacloud.contractmanagement.dto.CategoryResponse;
import com.costacloud.contractmanagement.model.Category;
import com.costacloud.contractmanagement.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryService")
class CategoryServiceTest {

    @Mock CategoryRepository categoryRepository;

    @InjectMocks CategoryService categoryService;

    private static final String CREATOR_EMAIL = "admin@test.com";

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Category savedCategory(String id, String name) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setCreatedBy(CREATOR_EMAIL);
        c.setCreatedAt(LocalDateTime.of(2025, 1, 10, 12, 0));
        return c;
    }

    private CategoryRequest requestFor(String name) {
        CategoryRequest r = new CategoryRequest();
        r.setName(name);
        return r;
    }

    private Category captureLastSaved() {
        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 1 — getAllCategories
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAllCategories")
    class GetAllCategories {

        @Test
        @DisplayName("returns a response for each category stored in the repository")
        void shouldReturn_allCategories() {
            when(categoryRepository.findAll()).thenReturn(List.of(
                    savedCategory("cat-01", "NDA"),
                    savedCategory("cat-02", "Employment")
            ));

            List<CategoryResponse> result = categoryService.getAllCategories();

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("maps id, name, createdBy, and createdAt correctly to CategoryResponse")
        void shouldMap_allFields_correctly() {
            when(categoryRepository.findAll()).thenReturn(List.of(
                    savedCategory("cat-01", "NDA")
            ));

            CategoryResponse r = categoryService.getAllCategories().get(0);

            assertEquals("cat-01",       r.getId());
            assertEquals("NDA",          r.getName());
            assertEquals(CREATOR_EMAIL,  r.getCreatedBy());
            assertNotNull(r.getCreatedAt());
        }

        @Test
        @DisplayName("returns an empty list when no categories exist")
        void shouldReturn_emptyList_whenNoCategoriesExist() {
            when(categoryRepository.findAll()).thenReturn(List.of());

            assertTrue(categoryService.getAllCategories().isEmpty());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 2 — createCategory: success
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createCategory — success")
    class CreateCategorySuccess {

        @BeforeEach
        void stubSave() {
            lenient().when(categoryRepository.existsByNameIgnoreCase(anyString())).thenReturn(false);
            lenient().when(categoryRepository.save(any())).thenAnswer(inv -> {
                Category c = inv.getArgument(0);
                c.setId("generated-id");
                return c;
            });
        }

        @Test
        @DisplayName("saves the category with the correct name from the request")
        void shouldSave_correctName() {
            categoryService.createCategory(requestFor("NDA"), CREATOR_EMAIL);

            assertEquals("NDA", captureLastSaved().getName());
        }

        @Test
        @DisplayName("saves the category with createdBy set to the authenticated user's email")
        void shouldSave_createdBy_fromEmail() {
            categoryService.createCategory(requestFor("NDA"), CREATOR_EMAIL);

            assertEquals(CREATOR_EMAIL, captureLastSaved().getCreatedBy());
        }

        @Test
        @DisplayName("saves the category with a non-null createdAt timestamp")
        void shouldSave_nonNull_createdAt() {
            categoryService.createCategory(requestFor("NDA"), CREATOR_EMAIL);

            assertNotNull(captureLastSaved().getCreatedAt());
        }

        @Test
        @DisplayName("returns a CategoryResponse with the correct name")
        void shouldReturn_categoryResponse_withCorrectName() {
            CategoryResponse response = categoryService.createCategory(requestFor("NDA"), CREATOR_EMAIL);

            assertEquals("NDA", response.getName());
        }

        @Test
        @DisplayName("returns a CategoryResponse with createdBy set to the caller's email")
        void shouldReturn_categoryResponse_withCreatedBy() {
            CategoryResponse response = categoryService.createCategory(requestFor("NDA"), CREATOR_EMAIL);

            assertEquals(CREATOR_EMAIL, response.getCreatedBy());
        }

        @Test
        @DisplayName("calls save exactly once when creating a new category")
        void shouldCallSave_exactlyOnce() {
            categoryService.createCategory(requestFor("NDA"), CREATOR_EMAIL);

            verify(categoryRepository, times(1)).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 3 — createCategory: duplicate name guard
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createCategory — duplicate name guard")
    class CreateCategoryDuplicateGuard {

        @Test
        @DisplayName("throws RuntimeException when a category with the same name already exists")
        void shouldThrow_whenNameAlreadyExists() {
            when(categoryRepository.existsByNameIgnoreCase("NDA")).thenReturn(true);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> categoryService.createCategory(requestFor("NDA"), CREATOR_EMAIL));

            assertTrue(ex.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("does not call save when the name already exists")
        void shouldNotCallSave_onDuplicate() {
            when(categoryRepository.existsByNameIgnoreCase("NDA")).thenReturn(true);

            assertThrows(RuntimeException.class,
                    () -> categoryService.createCategory(requestFor("NDA"), CREATOR_EMAIL));

            verify(categoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("duplicate check is case-insensitive (existsByNameIgnoreCase is called)")
        void shouldCheck_caseInsensitive() {
            when(categoryRepository.existsByNameIgnoreCase("nda")).thenReturn(false);
            lenient().when(categoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            categoryService.createCategory(requestFor("nda"), CREATOR_EMAIL);

            verify(categoryRepository).existsByNameIgnoreCase("nda");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GROUP 4 — deleteCategory
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteCategory")
    class DeleteCategory {

        @Test
        @DisplayName("calls deleteById with the correct ID when category exists")
        void shouldCall_deleteById_withCorrectId() {
            when(categoryRepository.existsById("cat-01")).thenReturn(true);

            categoryService.deleteCategory("cat-01");

            verify(categoryRepository, times(1)).deleteById("cat-01");
        }

        @Test
        @DisplayName("throws RuntimeException when category ID does not exist")
        void shouldThrow_whenCategoryNotFound() {
            when(categoryRepository.existsById("nonexistent")).thenReturn(false);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> categoryService.deleteCategory("nonexistent"));

            assertTrue(ex.getMessage().contains("not found"));
        }

        @Test
        @DisplayName("does not call deleteById when category does not exist")
        void shouldNotCallDeleteById_whenNotFound() {
            when(categoryRepository.existsById("nonexistent")).thenReturn(false);

            assertThrows(RuntimeException.class,
                    () -> categoryService.deleteCategory("nonexistent"));

            verify(categoryRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("completes without throwing when the category exists")
        void shouldNotThrow_whenCategoryExists() {
            when(categoryRepository.existsById("cat-01")).thenReturn(true);
            doNothing().when(categoryRepository).deleteById("cat-01");

            assertDoesNotThrow(() -> categoryService.deleteCategory("cat-01"));
        }
    }
}
