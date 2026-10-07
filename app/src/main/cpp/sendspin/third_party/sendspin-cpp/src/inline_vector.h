// Copyright 2026 Sendspin Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/// @file inline_vector.h
/// @brief Fixed-capacity vector whose storage lives inside the object

#pragma once

#include <algorithm>
#include <array>
#include <cassert>
#include <cstddef>
#include <type_traits>
#include <utility>

namespace sendspin {

/// @brief Vector with a compile-time capacity and inline storage; never touches the heap
///
/// For containers with a proven size bound. erase() and clear() overwrite every removed element
/// (by the shift or a reset to T{}), so its resources (e.g. a shared_ptr reference) are released
/// at the removal.
///
/// Requires T to be default-constructible, move-constructible, and move-assignable. Not copyable
/// or movable; use swap().
template <typename T, size_t N>
class InlineVector {
public:
    using iterator = T*;
    using const_iterator = const T*;

    InlineVector() = default;
    InlineVector(const InlineVector&) = delete;
    InlineVector& operator=(const InlineVector&) = delete;

    /// @brief Appends `value`. Precondition: size() < N.
    /// @param value The element to append (moved in).
    void push_back(T value) {
        assert(this->count_ < N && "InlineVector capacity exceeded");
        this->items_[this->count_++] = std::move(value);
    }

    /// @brief Removes the element at `pos`, shifting later elements down to keep their order.
    /// @param pos Valid iterator into this vector.
    /// @return Iterator to the element that followed the removed one.
    iterator erase(iterator pos) {
        std::move(pos + 1, this->end(), pos);
        this->items_[--this->count_] = T{};
        return pos;
    }

    /// @brief Removes every element.
    void clear() {
        for (auto& item : *this) {
            item = T{};
        }
        this->count_ = 0;
    }

    /// @brief Exchanges contents with `other`.
    /// @param other The vector to swap with.
    void swap(InlineVector& other) noexcept(std::is_nothrow_swappable_v<T>) {
        this->items_.swap(other.items_);
        std::swap(this->count_, other.count_);
    }

    size_t size() const {
        return this->count_;
    }
    bool empty() const {
        return this->count_ == 0;
    }
    static constexpr size_t capacity() {
        return N;
    }

    iterator begin() {
        return this->items_.data();
    }
    iterator end() {
        return this->items_.data() + this->count_;
    }
    const_iterator begin() const {
        return this->items_.data();
    }
    const_iterator end() const {
        return this->items_.data() + this->count_;
    }

    T& operator[](size_t i) {
        return this->items_[i];
    }
    const T& operator[](size_t i) const {
        return this->items_[i];
    }

private:
    // Struct fields
    std::array<T, N> items_{};

    // size_t fields
    size_t count_{0};
};

}  // namespace sendspin
