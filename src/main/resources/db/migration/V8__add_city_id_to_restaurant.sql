ALTER TABLE restaurant
  ADD COLUMN city_id int REFERENCES cities(id);
