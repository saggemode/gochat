-- business/014_fix_published_products.sql
-- Fix any products that were erroneously marked as unpublished when updated

UPDATE business.products
SET is_published = TRUE
WHERE is_published = FALSE;
