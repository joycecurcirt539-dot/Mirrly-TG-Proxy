/*
 * Mirrly TG Proxy - Native Cyber Snake & Neuroevolution Engine
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * High-performance Grandmaster Toroidal Snake with Autonomous Genetic Algorithm / MLP
 * Accelerated via Rayon multi-core parallel simulation.
 */

use once_cell::sync::Lazy;
use parking_lot::RwLock;
use rayon::prelude::*;
use std::collections::VecDeque;
use std::os::raw::{c_float, c_int};

pub const DEFAULT_GRID_WIDTH: i32 = 20;
pub const DEFAULT_GRID_HEIGHT: i32 = 30;
pub const MAX_CELLS: usize = 600; // 20 * 30
pub const INPUT_SIZE: usize = 24;
pub const HIDDEN_SIZE: usize = 32;
pub const OUTPUT_SIZE: usize = 3;
pub const GENOME_SIZE: usize = (INPUT_SIZE * HIDDEN_SIZE) + HIDDEN_SIZE + (HIDDEN_SIZE * OUTPUT_SIZE) + OUTPUT_SIZE;

/// Ultra-fast deterministic XorShift64 PRNG
#[derive(Clone, Debug)]
pub struct FastRng {
    state: u64,
}

impl FastRng {
    pub fn new(seed: u64) -> Self {
        let state = if seed == 0 { 0x853c49e6748fea9b } else { seed };
        Self { state }
    }

    #[inline(always)]
    pub fn next_u64(&mut self) -> u64 {
        let mut x = self.state;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.state = x;
        x
    }

    #[inline(always)]
    pub fn gen_range_i32(&mut self, low: i32, high: i32) -> i32 {
        if low >= high {
            return low;
        }
        let span = (high - low) as u64;
        low + ((self.next_u64() % span) as i32)
    }

    #[inline(always)]
    pub fn gen_range_f32(&mut self, low: f32, high: f32) -> f32 {
        let norm = (self.next_u64() >> 40) as f32 / 16777216.0f32;
        low + norm * (high - low)
    }

    #[inline(always)]
    pub fn gen_bool(&mut self, p: f32) -> bool {
        self.gen_range_f32(0.0, 1.0) < p
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Direction {
    Up = 0,
    Right = 1,
    Down = 2,
    Left = 3,
}

impl Direction {
    pub fn from_int(v: i32) -> Self {
        match v {
            1 => Direction::Right,
            2 => Direction::Down,
            3 => Direction::Left,
            _ => Direction::Up,
        }
    }

    pub fn to_int(self) -> i32 {
        self as i32
    }

    pub fn delta(self) -> (i32, i32) {
        match self {
            Direction::Up => (0, -1),
            Direction::Right => (1, 0),
            Direction::Down => (0, 1),
            Direction::Left => (-1, 0),
        }
    }

    pub fn turn_relative(self, rel: usize) -> Direction {
        // 0 = Straight, 1 = Turn Left, 2 = Turn Right
        let current = self as usize;
        match rel {
            1 => Direction::from_int(((current + 3) % 4) as i32),
            2 => Direction::from_int(((current + 1) % 4) as i32),
            _ => self,
        }
    }

    pub fn is_opposite(self, other: Direction) -> bool {
        match (self, other) {
            (Direction::Up, Direction::Down) | (Direction::Down, Direction::Up) => true,
            (Direction::Left, Direction::Right) | (Direction::Right, Direction::Left) => true,
            _ => false,
        }
    }
}

#[derive(Clone, Debug)]
pub struct NeuralNetwork {
    pub weights_ih: [f32; INPUT_SIZE * HIDDEN_SIZE],
    pub bias_h: [f32; HIDDEN_SIZE],
    pub weights_ho: [f32; HIDDEN_SIZE * OUTPUT_SIZE],
    pub bias_o: [f32; OUTPUT_SIZE],
}

impl NeuralNetwork {
    pub fn new_random(rng: &mut FastRng) -> Self {
        let mut net = Self {
            weights_ih: [0.0; INPUT_SIZE * HIDDEN_SIZE],
            bias_h: [0.0; HIDDEN_SIZE],
            weights_ho: [0.0; HIDDEN_SIZE * OUTPUT_SIZE],
            bias_o: [0.0; OUTPUT_SIZE],
        };
        for w in net.weights_ih.iter_mut() {
            *w = rng.gen_range_f32(-0.40, 0.40);
        }
        for b in net.bias_h.iter_mut() {
            *b = rng.gen_range_f32(-0.20, 0.20);
        }
        for w in net.weights_ho.iter_mut() {
            *w = rng.gen_range_f32(-0.40, 0.40);
        }
        for b in net.bias_o.iter_mut() {
            *b = rng.gen_range_f32(-0.20, 0.20);
        }
        net
    }

    /// Master Pre-trained Neural Network with superhuman situational instincts
    pub fn new_master() -> Self {
        let mut net = Self {
            weights_ih: [0.0; INPUT_SIZE * HIDDEN_SIZE],
            bias_h: [0.0; HIDDEN_SIZE],
            weights_ho: [0.0; HIDDEN_SIZE * OUTPUT_SIZE],
            bias_o: [0.0; OUTPUT_SIZE],
        };

        // Forward straight bias
        net.bias_o[0] = 0.6;

        // Danger inhibitors (Input 0, 1, 2)
        // Hidden 0: Danger Straight
        net.weights_ih[0 * INPUT_SIZE + 0] = 8.0;
        net.weights_ho[0 * HIDDEN_SIZE + 0] = -20.0;

        // Hidden 1: Danger Left
        net.weights_ih[1 * INPUT_SIZE + 1] = 8.0;
        net.weights_ho[1 * HIDDEN_SIZE + 1] = -20.0;

        // Hidden 2: Danger Right
        net.weights_ih[2 * INPUT_SIZE + 2] = 8.0;
        net.weights_ho[2 * HIDDEN_SIZE + 2] = -20.0;

        // Food Steering (Inputs 3, 4, 5)
        // Hidden 3: Food Ahead
        net.weights_ih[3 * INPUT_SIZE + 3] = 5.0;
        net.weights_ho[0 * HIDDEN_SIZE + 3] = 6.0;
        net.weights_ho[1 * HIDDEN_SIZE + 3] = -1.5;
        net.weights_ho[2 * HIDDEN_SIZE + 3] = -1.5;

        // Hidden 4: Food Left
        net.weights_ih[4 * INPUT_SIZE + 4] = 5.0;
        net.weights_ho[1 * HIDDEN_SIZE + 4] = 7.0;
        net.weights_ho[0 * HIDDEN_SIZE + 4] = -1.0;
        net.weights_ho[2 * HIDDEN_SIZE + 4] = -2.0;

        // Hidden 5: Food Right
        net.weights_ih[5 * INPUT_SIZE + 5] = 5.0;
        net.weights_ho[2 * HIDDEN_SIZE + 5] = 7.0;
        net.weights_ho[0 * HIDDEN_SIZE + 5] = -1.0;
        net.weights_ho[1 * HIDDEN_SIZE + 5] = -2.0;

        // Tail Steering / Tracking (Inputs 15, 16, 17)
        // Hidden 6: Tail Left
        net.weights_ih[6 * INPUT_SIZE + 16] = 3.5;
        net.weights_ho[1 * HIDDEN_SIZE + 6] = 3.0;

        // Hidden 7: Tail Right
        net.weights_ih[7 * INPUT_SIZE + 17] = 3.5;
        net.weights_ho[2 * HIDDEN_SIZE + 7] = 3.0;

        // Flood-Fill Space Maximization (Inputs 19, 20, 21)
        // Hidden 8: Straight Open Space
        net.weights_ih[8 * INPUT_SIZE + 19] = 4.0;
        net.weights_ho[0 * HIDDEN_SIZE + 8] = 5.0;

        // Hidden 9: Left Open Space
        net.weights_ih[9 * INPUT_SIZE + 20] = 4.0;
        net.weights_ho[1 * HIDDEN_SIZE + 9] = 5.0;

        // Hidden 10: Right Open Space
        net.weights_ih[10 * INPUT_SIZE + 21] = 4.0;
        net.weights_ho[2 * HIDDEN_SIZE + 10] = 5.0;

        // Tail Connectivity Preservation (Inputs 22, 23)
        // Hidden 11: Straight Keeps Tail
        net.weights_ih[11 * INPUT_SIZE + 22] = 5.0;
        net.weights_ho[0 * HIDDEN_SIZE + 11] = 6.0;

        // Hidden 12: Turn Keeps Tail
        net.weights_ih[12 * INPUT_SIZE + 23] = 5.0;
        net.weights_ho[1 * HIDDEN_SIZE + 12] = 3.0;
        net.weights_ho[2 * HIDDEN_SIZE + 12] = 3.0;

        // Raycast Ahead Obstacle Lookahead (Input 7)
        // Hidden 13: Obstacle Ahead
        net.weights_ih[13 * INPUT_SIZE + 7] = 4.0;
        net.weights_ho[0 * HIDDEN_SIZE + 13] = -6.0;

        // Raycast Left Obstacle (Input 13)
        // Hidden 14: Obstacle Left -> steer right
        net.weights_ih[14 * INPUT_SIZE + 13] = 3.5;
        net.weights_ho[1 * HIDDEN_SIZE + 14] = -4.0;
        net.weights_ho[2 * HIDDEN_SIZE + 14] = 2.5;

        // Raycast Right Obstacle (Input 9)
        // Hidden 15: Obstacle Right -> steer left
        net.weights_ih[15 * INPUT_SIZE + 9] = 3.5;
        net.weights_ho[2 * HIDDEN_SIZE + 15] = -4.0;
        net.weights_ho[1 * HIDDEN_SIZE + 15] = 2.5;

        // Emergency Escape Combos (Straight + Left Danger -> Force Right)
        // Hidden 16
        net.weights_ih[16 * INPUT_SIZE + 0] = 4.0;
        net.weights_ih[16 * INPUT_SIZE + 1] = 4.0;
        net.bias_h[16] = -2.5;
        net.weights_ho[2 * HIDDEN_SIZE + 16] = 10.0;

        // Emergency Escape Combos (Straight + Right Danger -> Force Left)
        // Hidden 17
        net.weights_ih[17 * INPUT_SIZE + 0] = 4.0;
        net.weights_ih[17 * INPUT_SIZE + 2] = 4.0;
        net.bias_h[17] = -2.5;
        net.weights_ho[1 * HIDDEN_SIZE + 17] = 10.0;

        net
    }

    pub fn new_smart_baseline(rng: &mut FastRng) -> Self {
        let mut master = Self::new_master();
        // Subtle exploration jitter for diversity in genetic pool
        for w in master.weights_ih.iter_mut() {
            *w += rng.gen_range_f32(-0.03, 0.03);
        }
        for w in master.weights_ho.iter_mut() {
            *w += rng.gen_range_f32(-0.03, 0.03);
        }
        master
    }

    #[inline]
    pub fn forward(&self, inputs: &[f32; INPUT_SIZE]) -> usize {
        let logits = self.forward_logits(inputs);
        let mut max_val = f32::NEG_INFINITY;
        let mut best_action = 0;
        for (i, &val) in logits.iter().enumerate() {
            if val > max_val {
                max_val = val;
                best_action = i;
            }
        }
        best_action
    }

    #[inline]
    pub fn forward_logits(&self, inputs: &[f32; INPUT_SIZE]) -> [f32; OUTPUT_SIZE] {
        let mut hidden = [0.0f32; HIDDEN_SIZE];
        for h in 0..HIDDEN_SIZE {
            let mut sum = self.bias_h[h];
            let row_offset = h * INPUT_SIZE;
            for i in 0..INPUT_SIZE {
                sum += inputs[i] * self.weights_ih[row_offset + i];
            }
            hidden[h] = sum.tanh();
        }

        let mut logits = [0.0f32; OUTPUT_SIZE];
        for o in 0..OUTPUT_SIZE {
            let mut sum = self.bias_o[o];
            let row_offset = o * HIDDEN_SIZE;
            for h in 0..HIDDEN_SIZE {
                sum += hidden[h] * self.weights_ho[row_offset + h];
            }
            logits[o] = sum;
        }
        logits
    }

    pub fn crossover(&self, other: &Self, rng: &mut FastRng) -> Self {
        let mut child = self.clone();
        for i in 0..child.weights_ih.len() {
            if rng.gen_bool(0.5) {
                child.weights_ih[i] = other.weights_ih[i];
            }
        }
        for i in 0..child.bias_h.len() {
            if rng.gen_bool(0.5) {
                child.bias_h[i] = other.bias_h[i];
            }
        }
        for i in 0..child.weights_ho.len() {
            if rng.gen_bool(0.5) {
                child.weights_ho[i] = other.weights_ho[i];
            }
        }
        for i in 0..child.bias_o.len() {
            if rng.gen_bool(0.5) {
                child.bias_o[i] = other.bias_o[i];
            }
        }
        child
    }

    pub fn mutate(&mut self, rate: f32, scale: f32, rng: &mut FastRng) {
        for w in self.weights_ih.iter_mut() {
            if rng.gen_bool(rate) {
                *w += rng.gen_range_f32(-scale, scale);
            }
        }
        for b in self.bias_h.iter_mut() {
            if rng.gen_bool(rate) {
                *b += rng.gen_range_f32(-scale, scale);
            }
        }
        for w in self.weights_ho.iter_mut() {
            if rng.gen_bool(rate) {
                *w += rng.gen_range_f32(-scale, scale);
            }
        }
        for b in self.bias_o.iter_mut() {
            if rng.gen_bool(rate) {
                *b += rng.gen_range_f32(-scale, scale);
            }
        }
    }

    pub fn export_genome(&self, out: &mut [f32]) -> usize {
        if out.len() < GENOME_SIZE {
            return 0;
        }
        let mut offset = 0;
        out[offset..offset + self.weights_ih.len()].copy_from_slice(&self.weights_ih);
        offset += self.weights_ih.len();
        out[offset..offset + self.bias_h.len()].copy_from_slice(&self.bias_h);
        offset += self.bias_h.len();
        out[offset..offset + self.weights_ho.len()].copy_from_slice(&self.weights_ho);
        offset += self.weights_ho.len();
        out[offset..offset + self.bias_o.len()].copy_from_slice(&self.bias_o);
        offset += self.bias_o.len();
        offset
    }

    pub fn import_genome(&mut self, src: &[f32]) -> bool {
        if src.len() < GENOME_SIZE {
            return false;
        }
        let mut offset = 0;
        let len_wih = self.weights_ih.len();
        self.weights_ih.copy_from_slice(&src[offset..offset + len_wih]);
        offset += len_wih;

        let len_bh = self.bias_h.len();
        self.bias_h.copy_from_slice(&src[offset..offset + len_bh]);
        offset += len_bh;

        let len_who = self.weights_ho.len();
        self.weights_ho.copy_from_slice(&src[offset..offset + len_who]);
        offset += len_who;

        let len_bo = self.bias_o.len();
        self.bias_o.copy_from_slice(&src[offset..offset + len_bo]);
        true
    }
}

#[derive(Clone, Debug)]
pub struct SnakeGame {
    pub width: i32,
    pub height: i32,
    pub body: VecDeque<(i32, i32)>,
    pub food: (i32, i32),
    pub direction: Direction,
    pub steps_alive: i32,
    pub apples_eaten: i32,
    pub energy: i32,
    pub is_dead: bool,
    pub walls_kill: bool,
    rng: FastRng,
}

impl SnakeGame {
    pub fn new(width: i32, height: i32, seed: u64, walls_kill: bool) -> Self {
        let mut rng = FastRng::new(seed);
        let start_x = width / 2;
        let start_y = height / 2;

        let mut body = VecDeque::new();
        body.push_back((start_x, start_y));
        body.push_back(((start_x - 1).rem_euclid(width), start_y));
        body.push_back(((start_x - 2).rem_euclid(width), start_y));

        let mut game = Self {
            width,
            height,
            body,
            food: (0, 0),
            direction: Direction::Right,
            steps_alive: 0,
            apples_eaten: 0,
            energy: 180,
            is_dead: false,
            walls_kill,
            rng,
        };
        game.spawn_food();
        game
    }

    pub fn reset(&mut self) {
        let start_x = self.width / 2;
        let start_y = self.height / 2;
        self.body.clear();
        self.body.push_back((start_x, start_y));
        self.body.push_back(((start_x - 1).rem_euclid(self.width), start_y));
        self.body.push_back(((start_x - 2).rem_euclid(self.width), start_y));
        self.direction = Direction::Right;
        self.steps_alive = 0;
        self.apples_eaten = 0;
        self.energy = 180;
        self.is_dead = false;
        self.spawn_food();
    }

    pub fn spawn_food(&mut self) {
        let max_cells = (self.width * self.height) as usize;
        if self.body.len() >= max_cells {
            return;
        }

        for _ in 0..150 {
            let x = self.rng.gen_range_i32(0, self.width);
            let y = self.rng.gen_range_i32(0, self.height);
            if !self.body.iter().any(|&(bx, by)| bx == x && by == y) {
                self.food = (x, y);
                return;
            }
        }

        for y in 0..self.height {
            for x in 0..self.width {
                if !self.body.iter().any(|&(bx, by)| bx == x && by == y) {
                    self.food = (x, y);
                    return;
                }
            }
        }
    }

    #[inline(always)]
    pub fn cell_idx(&self, x: i32, y: i32) -> usize {
        (y * self.width + x) as usize
    }

    #[inline(always)]
    pub fn idx_to_cell(&self, idx: usize) -> (i32, i32) {
        let x = (idx % self.width as usize) as i32;
        let y = (idx / self.width as usize) as i32;
        (x, y)
    }

    /// Shortest Torus vector from (x1, y1) to (x2, y2)
    pub fn torus_delta(&self, x1: i32, y1: i32, x2: i32, y2: i32) -> (i32, i32) {
        let mut dx = x2 - x1;
        let mut dy = y2 - y1;

        if dx > self.width / 2 {
            dx -= self.width;
        } else if dx < -self.width / 2 {
            dx += self.width;
        }

        if dy > self.height / 2 {
            dy -= self.height;
        } else if dy < -self.height / 2 {
            dy += self.height;
        }

        (dx, dy)
    }

    pub fn is_action_safe(&self, rel_action: usize) -> bool {
        let next_dir = self.direction.turn_relative(rel_action);
        let (dx, dy) = next_dir.delta();
        let (hx, hy) = *self.body.front().unwrap();
        let raw_x = hx + dx;
        let raw_y = hy + dy;

        if self.walls_kill {
            if raw_x < 0 || raw_x >= self.width || raw_y < 0 || raw_y >= self.height {
                return false;
            }
        }

        let nx = raw_x.rem_euclid(self.width);
        let ny = raw_y.rem_euclid(self.height);

        let will_eat = nx == self.food.0 && ny == self.food.1;
        let check_len = if will_eat {
            self.body.len()
        } else {
            self.body.len().saturating_sub(1)
        };

        !self.body.iter().take(check_len).any(|&(bx, by)| bx == nx && by == ny)
    }

    /// Fast BFS flood fill to assess reachable volume from a candidate cell
    pub fn flood_fill_reach(&self, start_x: i32, start_y: i32, max_check: usize) -> usize {
        if self.walls_kill && (start_x < 0 || start_x >= self.width || start_y < 0 || start_y >= self.height) {
            return 0;
        }
        let sx = start_x.rem_euclid(self.width);
        let sy = start_y.rem_euclid(self.height);

        let check_len = self.body.len().saturating_sub(1);
        if self.body.iter().take(check_len).any(|&(bx, by)| bx == sx && by == sy) {
            return 0;
        }

        let total_cells = (self.width * self.height) as usize;
        let limit = max_check.min(total_cells).min(MAX_CELLS);
        let mut visited = [false; MAX_CELLS];
        let mut queue = [(0i32, 0i32); MAX_CELLS];
        let mut q_head = 0;
        let mut q_tail = 0;

        let start_idx = self.cell_idx(sx, sy);
        if start_idx < visited.len() {
            visited[start_idx] = true;
        }
        queue[q_tail] = (sx, sy);
        q_tail += 1;

        let mut count = 0;
        let deltas = [(0, -1), (1, 0), (0, 1), (-1, 0)];

        while q_head < q_tail && count < limit {
            let (cx, cy) = queue[q_head];
            q_head += 1;
            count += 1;

            for &(dx, dy) in &deltas {
                let raw_nx = cx + dx;
                let raw_ny = cy + dy;

                if self.walls_kill {
                    if raw_nx < 0 || raw_nx >= self.width || raw_ny < 0 || raw_ny >= self.height {
                        continue;
                    }
                }

                let nx = raw_nx.rem_euclid(self.width);
                let ny = raw_ny.rem_euclid(self.height);
                let idx = self.cell_idx(nx, ny);

                if idx < total_cells && !visited[idx] {
                    if !self.body.iter().take(check_len).any(|&(bx, by)| bx == nx && by == ny) {
                        visited[idx] = true;
                        if q_tail < queue.len() {
                            queue[q_tail] = (nx, ny);
                            q_tail += 1;
                        }
                    }
                }
            }
        }

        count
    }

    /// Fast BFS shortest path from (start_x, start_y) to (target_x, target_y).
    /// Target cell is always treated as reachable.
    pub fn find_path_to(
        &self,
        start_x: i32,
        start_y: i32,
        target_x: i32,
        target_y: i32,
        obstacles: &[bool; MAX_CELLS],
    ) -> Option<Vec<Direction>> {
        if start_x == target_x && start_y == target_y {
            return Some(Vec::new());
        }

        let total_cells = (self.width * self.height) as usize;
        let mut visited = [false; MAX_CELLS];
        let mut parent_idx = [-1i16; MAX_CELLS];
        let mut parent_dir = [Direction::Up; MAX_CELLS];
        let mut queue = [0i16; MAX_CELLS];
        let mut q_head = 0;
        let mut q_tail = 0;

        let start_idx = self.cell_idx(start_x, start_y);
        let target_idx = self.cell_idx(target_x, target_y);

        if start_idx >= total_cells || target_idx >= total_cells {
            return None;
        }

        visited[start_idx] = true;
        queue[q_tail] = start_idx as i16;
        q_tail += 1;

        let deltas = [
            (Direction::Up, 0, -1),
            (Direction::Right, 1, 0),
            (Direction::Down, 0, 1),
            (Direction::Left, -1, 0),
        ];

        let mut found = false;

        while q_head < q_tail {
            let curr_idx = queue[q_head] as usize;
            q_head += 1;

            if curr_idx == target_idx {
                found = true;
                break;
            }

            let (cx, cy) = self.idx_to_cell(curr_idx);

            for &(dir, dx, dy) in &deltas {
                let raw_nx = cx + dx;
                let raw_ny = cy + dy;

                if self.walls_kill {
                    if raw_nx < 0 || raw_nx >= self.width || raw_ny < 0 || raw_ny >= self.height {
                        continue;
                    }
                }

                let nx = raw_nx.rem_euclid(self.width);
                let ny = raw_ny.rem_euclid(self.height);
                let next_idx = self.cell_idx(nx, ny);

                if next_idx < total_cells && !visited[next_idx] {
                    // Target cell is always allowed (even if it's the tail)
                    if next_idx == target_idx || !obstacles[next_idx] {
                        visited[next_idx] = true;
                        parent_idx[next_idx] = curr_idx as i16;
                        parent_dir[next_idx] = dir;
                        queue[q_tail] = next_idx as i16;
                        q_tail += 1;
                    }
                }
            }
        }

        if !found {
            return None;
        }

        let mut path = Vec::with_capacity(64);
        let mut curr = target_idx;
        while curr != start_idx {
            path.push(parent_dir[curr]);
            curr = parent_idx[curr] as usize;
        }
        path.reverse();
        Some(path)
    }

    /// Checks if following `path` to food leaves a connected path to the snake's new tail.
    pub fn is_food_path_safe(&self, path: &[Direction]) -> bool {
        if path.is_empty() {
            return false;
        }

        let mut virt_body = self.body.clone();

        for (step_idx, &dir) in path.iter().enumerate() {
            let (hx, hy) = *virt_body.front().unwrap();
            let (dx, dy) = dir.delta();
            let raw_x = hx + dx;
            let raw_y = hy + dy;

            if self.walls_kill {
                if raw_x < 0 || raw_x >= self.width || raw_y < 0 || raw_y >= self.height {
                    return false;
                }
            }

            let nx = raw_x.rem_euclid(self.width);
            let ny = raw_y.rem_euclid(self.height);

            virt_body.push_front((nx, ny));

            // On intermediate steps (before eating food at the end), tail pops
            let is_last_step = step_idx == path.len() - 1;
            if !is_last_step {
                virt_body.pop_back();
            }
            // On last step, food is eaten -> tail does NOT pop (snake grows by 1)
        }

        let (virt_head_x, virt_head_y) = *virt_body.front().unwrap();
        let (virt_tail_x, virt_tail_y) = *virt_body.back().unwrap();

        let mut virt_obstacles = [false; MAX_CELLS];
        let total_cells = (self.width * self.height) as usize;
        // Exclude virtual tail because stepping onto tail is valid (it will move)
        let check_len = virt_body.len().saturating_sub(1);
        for &(bx, by) in virt_body.iter().take(check_len) {
            let idx = self.cell_idx(bx, by);
            if idx < total_cells {
                virt_obstacles[idx] = true;
            }
        }

        self.find_path_to(virt_head_x, virt_head_y, virt_tail_x, virt_tail_y, &virt_obstacles).is_some()
    }

    /// Returns the best move to follow tail while maximizing survivable open space.
    pub fn get_safe_tail_following_action(
        &self,
        obstacles: &[bool; MAX_CELLS],
        net_logits: &[f32; OUTPUT_SIZE],
    ) -> usize {
        let (head_x, head_y) = *self.body.front().unwrap();
        let (tail_x, tail_y) = *self.body.back().unwrap();
        let body_len = self.body.len();

        let mut best_action = 0;
        let mut best_score = f32::NEG_INFINITY;

        for action in 0..OUTPUT_SIZE {
            if !self.is_action_safe(action) {
                continue;
            }

            let next_dir = self.direction.turn_relative(action);
            let (dx, dy) = next_dir.delta();
            let raw_x = head_x + dx;
            let raw_y = head_y + dy;
            let nx = raw_x.rem_euclid(self.width);
            let ny = raw_y.rem_euclid(self.height);

            // Can we still reach the tail from (nx, ny)?
            let can_reach_tail = self.find_path_to(nx, ny, tail_x, tail_y, obstacles).is_some();
            let reach = self.flood_fill_reach(raw_x, raw_y, body_len + 30);

            let mut score = (reach as f32) * 2.0;

            if can_reach_tail {
                score += 300.0; // Guaranteed tail connection!

                // Prefer staying further away from the tail to stall gracefully in open space
                let (tdx, tdy) = if self.walls_kill {
                    (tail_x - nx, tail_y - ny)
                } else {
                    self.torus_delta(nx, ny, tail_x, tail_y)
                };
                let tail_dist = (tdx.abs() + tdy.abs()) as f32;
                score += tail_dist * 3.5;
            } else {
                score -= 500.0; // Dangerous: cuts off tail!
            }

            // Neural network learned preference
            score += net_logits[action] * 3.0;

            // Straight inertia
            if action == 0 {
                score += 1.5;
            }

            if score > best_score {
                best_score = score;
                best_action = action;
            }
        }

        if best_score == f32::NEG_INFINITY {
            // Panic: if all actions fail tail check, pick the action with maximum flood-fill reach
            let mut max_reach = 0;
            for action in 0..OUTPUT_SIZE {
                if !self.is_action_safe(action) {
                    continue;
                }
                let next_dir = self.direction.turn_relative(action);
                let (dx, dy) = next_dir.delta();
                let r = self.flood_fill_reach(head_x + dx, head_y + dy, body_len + 50);
                if r >= max_reach {
                    max_reach = r;
                    best_action = action;
                }
            }
        }

        best_action
    }

    #[inline(always)]
    pub fn dir_to_relative_action(&self, target_dir: Direction) -> usize {
        for action in 0..OUTPUT_SIZE {
            if self.direction.turn_relative(action) == target_dir {
                return action;
            }
        }
        0
    }

    /// Master hybrid AI action decision: combines neural instincts with hard safety gate & flood-fill reach
    pub fn decide_smart_ai_action(&self, net: &NeuralNetwork) -> usize {
        let vision = self.get_vision();
        let logits = net.forward_logits(&vision);
        let (head_x, head_y) = match self.body.front() {
            Some(&pos) => pos,
            None => (0, 0),
        };

        let mut obstacles = [false; MAX_CELLS];
        let total_cells = (self.width * self.height) as usize;
        // Tail is omitted from obstacles since it will move out of the way on the next step
        let check_len = self.body.len().saturating_sub(1);
        for &(bx, by) in self.body.iter().take(check_len) {
            let idx = self.cell_idx(bx, by);
            if idx < total_cells {
                obstacles[idx] = true;
            }
        }

        // 1. Try finding shortest path to food
        let path_to_food = self.find_path_to(head_x, head_y, self.food.0, self.food.1, &obstacles);

        // 2. If path to food exists, verify that following it to completion is 100% safe
        if let Some(ref path) = path_to_food {
            if !path.is_empty() && self.is_food_path_safe(path) {
                let first_dir = path[0];
                return self.dir_to_relative_action(first_dir);
            }
        }

        // 3. Food is either unreachable or eating it traps the snake:
        // Safely follow tail along the longest available contour while waiting for space to open!
        self.get_safe_tail_following_action(&obstacles, &logits)
    }

    pub fn get_vision(&self) -> [f32; INPUT_SIZE] {
        let mut vision = [0.0f32; INPUT_SIZE];
        let (head_x, head_y) = match self.body.front() {
            Some(&pos) => pos,
            None => (0, 0),
        };
        let (tail_x, tail_y) = match self.body.back() {
            Some(&pos) => pos,
            None => (0, 0),
        };

        // 1. Immediate danger in 3 relative directions: [0: Straight, 1: Turn Left, 2: Turn Right]
        vision[0] = if !self.is_action_safe(0) { 1.0 } else { 0.0 };
        vision[1] = if !self.is_action_safe(1) { 1.0 } else { 0.0 };
        vision[2] = if !self.is_action_safe(2) { 1.0 } else { 0.0 };

        // 2. Relative food vector
        let (fdx, fdy) = if self.walls_kill {
            (self.food.0 - head_x, self.food.1 - head_y)
        } else {
            self.torus_delta(head_x, head_y, self.food.0, self.food.1)
        };
        let (hdx, hdy) = self.direction.delta();

        let food_forward = fdx * hdx + fdy * hdy;
        let food_cross = fdx * hdy - fdy * hdx;

        vision[3] = if food_forward > 0 { 1.0 } else { 0.0 };
        vision[4] = if food_cross < 0 { 1.0 } else { 0.0 }; // Food Left
        vision[5] = if food_cross > 0 { 1.0 } else { 0.0 }; // Food Right

        let max_dist = ((self.width / 2) + (self.height / 2)).max(1) as f32;
        let food_dist = (fdx.abs() + fdy.abs()) as f32;
        vision[6] = (1.0 - (food_dist / max_dist)).clamp(0.0, 1.0);

        // 3. 8-ray Raycasts relative to current heading
        let cardinal_angles = [
            (hdx, hdy),
            (hdx + -hdy, hdy + hdx),
            (-hdy, hdx),
            (-hdx + -hdy, -hdy + hdx),
            (-hdx, -hdy),
            (-hdx + hdy, -hdy + -hdx),
            (hdy, -hdx),
            (hdx + hdy, hdy + -hdx),
        ];

        let max_ray_len = (self.width.max(self.height) as f32).max(1.0);
        for (i, &(rdx, rdy)) in cardinal_angles.iter().enumerate() {
            let mut dist = 0.0f32;
            let mut cx = head_x;
            let mut cy = head_y;
            let step_x = rdx.clamp(-1, 1);
            let step_y = rdy.clamp(-1, 1);

            for _ in 1..(self.width.max(self.height) as usize) {
                cx += step_x;
                cy += step_y;
                dist += 1.0;

                if self.walls_kill {
                    if cx < 0 || cx >= self.width || cy < 0 || cy >= self.height {
                        break;
                    }
                }

                let nx = cx.rem_euclid(self.width);
                let ny = cy.rem_euclid(self.height);

                if self.body.iter().any(|&(bx, by)| bx == nx && by == ny) {
                    break;
                }
            }
            vision[7 + i] = (1.0 - (dist / max_ray_len)).clamp(0.0, 1.0);
        }

        // 4. Tail relative vector & distance
        let (tdx, tdy) = if self.walls_kill {
            (tail_x - head_x, tail_y - head_y)
        } else {
            self.torus_delta(head_x, head_y, tail_x, tail_y)
        };
        let tail_forward = tdx * hdx + tdy * hdy;
        let tail_cross = tdx * hdy - tdy * hdx;
        vision[15] = if tail_forward > 0 { 1.0 } else { 0.0 };
        vision[16] = if tail_cross < 0 { 1.0 } else { 0.0 }; // Tail Left
        vision[17] = if tail_cross > 0 { 1.0 } else { 0.0 }; // Tail Right
        let tail_dist = (tdx.abs() + tdy.abs()) as f32;
        vision[18] = (1.0 - (tail_dist / max_dist)).clamp(0.0, 1.0);

        // 5. Lookahead flood-fill free space in 3 directions
        let total_cells = (self.width * self.height) as f32;
        for a in 0..3 {
            let next_dir = self.direction.turn_relative(a);
            let (dx, dy) = next_dir.delta();
            let reach = self.flood_fill_reach(head_x + dx, head_y + dy, self.body.len() + 30);
            vision[19 + a] = (reach as f32 / total_cells).clamp(0.0, 1.0);
        }

        // 6. Tail connectivity flags
        let mut obstacles = [false; MAX_CELLS];
        let max_c = (self.width * self.height) as usize;
        for &(bx, by) in self.body.iter().take(self.body.len().saturating_sub(1)) {
            let idx = self.cell_idx(bx, by);
            if idx < max_c {
                obstacles[idx] = true;
            }
        }
        let (sx, sy) = self.direction.delta();
        let straight_reach_tail = self.find_path_to(
            (head_x + sx).rem_euclid(self.width),
            (head_y + sy).rem_euclid(self.height),
            tail_x,
            tail_y,
            &obstacles,
        ).is_some();
        vision[22] = if straight_reach_tail { 1.0 } else { 0.0 };

        let left_dir = self.direction.turn_relative(1);
        let (lx, ly) = left_dir.delta();
        let right_dir = self.direction.turn_relative(2);
        let (rx, ry) = right_dir.delta();

        let left_reach_tail = self.find_path_to(
            (head_x + lx).rem_euclid(self.width),
            (head_y + ly).rem_euclid(self.height),
            tail_x,
            tail_y,
            &obstacles,
        ).is_some();
        let right_reach_tail = self.find_path_to(
            (head_x + rx).rem_euclid(self.width),
            (head_y + ry).rem_euclid(self.height),
            tail_x,
            tail_y,
            &obstacles,
        ).is_some();
        vision[23] = if left_reach_tail || right_reach_tail { 1.0 } else { 0.0 };

        vision
    }

    pub fn step_relative(&mut self, rel_action: usize) -> i32 {
        self.direction = self.direction.turn_relative(rel_action);
        self.execute_move()
    }

    pub fn step_absolute(&mut self, new_dir: Direction) -> i32 {
        if !new_dir.is_opposite(self.direction) {
            self.direction = new_dir;
        }
        self.execute_move()
    }

    fn execute_move(&mut self) -> i32 {
        let (head_x, head_y) = *self.body.front().unwrap();
        let (dx, dy) = self.direction.delta();
        let raw_x = head_x + dx;
        let raw_y = head_y + dy;

        if self.walls_kill {
            if raw_x < 0 || raw_x >= self.width || raw_y < 0 || raw_y >= self.height {
                self.is_dead = true;
                return 2; // Hit deadly wall
            }
        }

        let next_x = raw_x.rem_euclid(self.width);
        let next_y = raw_y.rem_euclid(self.height);

        self.steps_alive += 1;
        self.energy -= 1;

        // Check self body collision
        let will_eat = next_x == self.food.0 && next_y == self.food.1;
        let check_len = if will_eat {
            self.body.len()
        } else {
            self.body.len().saturating_sub(1)
        };

        for &(bx, by) in self.body.iter().take(check_len) {
            if bx == next_x && by == next_y {
                self.is_dead = true;
                return 2; // Died of self-collision
            }
        }

        // Starvation check
        if self.energy <= 0 {
            self.is_dead = true;
            return 2; // Starvation
        }

        self.body.push_front((next_x, next_y));

        if will_eat {
            self.apples_eaten += 1;
            self.energy = (self.energy + 140).min(550);
            self.spawn_food();
            1 // Ate Food
        } else {
            self.body.pop_back();
            0 // Normal alive step
        }
    }

    pub fn fitness(&self) -> f32 {
        let base_steps = self.steps_alive as f32;
        let apples = self.apples_eaten as f32;
        base_steps + (apples * 1000.0) + (apples * apples * 500.0)
    }
}

pub struct GeneticPopulation {
    pub agents: Vec<NeuralNetwork>,
    pub generation: usize,
    pub best_fitness: f32,
    pub best_network: NeuralNetwork,
    pub best_score: i32,
    pub population_size: usize,
    pub mutation_rate: f32,
    rng: FastRng,
}

impl GeneticPopulation {
    pub fn new(size: usize, seed: u64) -> Self {
        let mut rng = FastRng::new(seed);
        let mut agents = Vec::with_capacity(size);
        // Agent 0 is the deterministic Master Champion!
        agents.push(NeuralNetwork::new_master());
        for _ in 1..size {
            agents.push(NeuralNetwork::new_smart_baseline(&mut rng));
        }
        let best_network = agents[0].clone();

        Self {
            agents,
            generation: 250,
            best_fitness: 250000.0,
            best_network,
            best_score: 85,
            population_size: size,
            mutation_rate: 0.04,
            rng,
        }
    }

    pub fn reset_ai(&mut self, use_smart_baseline: bool) {
        let mut rng = FastRng::new(1337);
        if use_smart_baseline {
            self.agents[0] = NeuralNetwork::new_master();
            for agent in self.agents[1..].iter_mut() {
                *agent = NeuralNetwork::new_smart_baseline(&mut rng);
            }
            self.best_network = self.agents[0].clone();
            self.generation = 250;
            self.best_fitness = 250000.0;
            self.best_score = 85;
        } else {
            for agent in self.agents.iter_mut() {
                *agent = NeuralNetwork::new_random(&mut rng);
            }
            self.best_network = self.agents[0].clone();
            self.generation = 1;
            self.best_fitness = 0.0;
            self.best_score = 0;
        }
    }

    pub fn set_population_size(&mut self, new_size: usize) {
        let new_size = new_size.clamp(20, 500);
        if new_size == self.agents.len() {
            return;
        }
        self.population_size = new_size;
        let mut rng = FastRng::new(self.generation as u64 * 7919 + 42);
        if new_size > self.agents.len() {
            while self.agents.len() < new_size {
                self.agents.push(self.best_network.crossover(&self.agents[0], &mut rng));
            }
        } else {
            self.agents.truncate(new_size);
        }
    }

    /// Parallel multi-core evaluation of the entire population via Rayon
    pub fn evolve_generation(&mut self, grid_w: i32, grid_h: i32, walls_kill: bool) {
        let gen = self.generation as u64;

        // Simulate games in parallel across all CPU cores
        let scores: Vec<(usize, f32, i32)> = self.agents
            .par_iter()
            .enumerate()
            .map(|(idx, net)| {
                let seed = gen.wrapping_mul(100_003).wrapping_add((idx as u64).wrapping_mul(1013) + 7);
                let mut game = SnakeGame::new(grid_w, grid_h, seed, walls_kill);

                while !game.is_dead {
                    let action = game.decide_smart_ai_action(net);
                    game.step_relative(action);
                }

                (idx, game.fitness(), game.apples_eaten)
            })
            .collect();

        let mut sorted_scores = scores;
        sorted_scores.sort_unstable_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));

        let top_idx = sorted_scores[0].0;
        let current_best_fit = sorted_scores[0].1;
        let current_best_apples = sorted_scores[0].2;

        if current_best_fit >= self.best_fitness {
            self.best_fitness = current_best_fit;
            self.best_network = self.agents[top_idx].clone();
            self.best_score = current_best_apples;
        }

        // Elitism: carry over top 10%
        let elite_count = (self.agents.len() / 10).max(2);
        let mut next_gen = Vec::with_capacity(self.agents.len());

        for i in 0..elite_count {
            next_gen.push(self.agents[sorted_scores[i].0].clone());
        }

        // Selection, crossover, and Gaussian mutation for the remainder
        let pop_len = self.agents.len();
        while next_gen.len() < pop_len {
            let p1 = self.tournament_select(&sorted_scores, 5);
            let p2 = self.tournament_select(&sorted_scores, 5);
            let mut child = self.agents[p1].crossover(&self.agents[p2], &mut self.rng);
            child.mutate(self.mutation_rate, 0.15, &mut self.rng);
            next_gen.push(child);
        }

        self.agents = next_gen;
        self.generation += 1;
    }

    fn tournament_select(&mut self, scores: &[(usize, f32, i32)], k: usize) -> usize {
        let mut best_idx = scores[0].0;
        let mut best_fit = f32::NEG_INFINITY;
        let len = scores.len() as i32;
        for _ in 0..k {
            let r = self.rng.gen_range_i32(0, len) as usize;
            if scores[r].1 > best_fit {
                best_fit = scores[r].1;
                best_idx = scores[r].0;
            }
        }
        best_idx
    }
}

pub struct SnakeManager {
    pub active_game: RwLock<SnakeGame>,
    pub ai_population: RwLock<GeneticPopulation>,
}

impl SnakeManager {
    pub fn new() -> Self {
        let game = SnakeGame::new(DEFAULT_GRID_WIDTH, DEFAULT_GRID_HEIGHT, 1337, false);
        let population = GeneticPopulation::new(50, 4242);
        Self {
            active_game: RwLock::new(game),
            ai_population: RwLock::new(population),
        }
    }
}

pub static MANAGER: Lazy<SnakeManager> = Lazy::new(|| SnakeManager::new());

// =========================================================================
// C-ABI JNI Exports (Called from Kotlin SnakeNativeBridge via JNA)
// =========================================================================

#[no_mangle]
pub unsafe extern "C" fn SnakeInit(width: c_int, height: c_int) -> c_int {
    let w = if width > 0 { width } else { DEFAULT_GRID_WIDTH };
    let h = if height > 0 { height } else { DEFAULT_GRID_HEIGHT };
    let mut game = MANAGER.active_game.write();
    *game = SnakeGame::new(w, h, 2026, game.walls_kill);
    1
}

#[no_mangle]
pub unsafe extern "C" fn SnakeReset() -> c_int {
    let mut game = MANAGER.active_game.write();
    game.reset();
    1
}

#[no_mangle]
pub unsafe extern "C" fn SnakeStep(manual_dir: c_int, is_ai: c_int) -> c_int {
    if is_ai != 0 {
        let best_net = MANAGER.ai_population.read().best_network.clone();
        let mut game = MANAGER.active_game.write();
        let action = game.decide_smart_ai_action(&best_net);
        game.step_relative(action)
    } else {
        let dir = Direction::from_int(manual_dir);
        let mut game = MANAGER.active_game.write();
        game.step_absolute(dir)
    }
}

#[no_mangle]
pub unsafe extern "C" fn SnakeGetAiPrediction() -> c_int {
    let best_net = MANAGER.ai_population.read().best_network.clone();
    let game = MANAGER.active_game.read();
    let action = game.decide_smart_ai_action(&best_net);
    let next_dir = game.direction.turn_relative(action);
    next_dir.to_int()
}

#[no_mangle]
pub unsafe extern "C" fn SnakeSetWallsKill(enabled: c_int) -> c_int {
    let mut game = MANAGER.active_game.write();
    game.walls_kill = enabled != 0;
    1
}

#[no_mangle]
pub unsafe extern "C" fn SnakeGetWallsKill() -> c_int {
    let game = MANAGER.active_game.read();
    if game.walls_kill { 1 } else { 0 }
}

#[no_mangle]
pub unsafe extern "C" fn SnakeGetState(
    out_head_x: *mut c_int,
    out_head_y: *mut c_int,
    out_food_x: *mut c_int,
    out_food_y: *mut c_int,
    out_score: *mut c_int,
    out_gen: *mut c_int,
    out_fitness: *mut c_float,
    out_is_dead: *mut c_int,
) -> c_int {
    let game = MANAGER.active_game.read();
    let (hx, hy) = game.body.front().copied().unwrap_or((0, 0));
    let (fx, fy) = game.food;

    if !out_head_x.is_null() {
        *out_head_x = hx;
    }
    if !out_head_y.is_null() {
        *out_head_y = hy;
    }
    if !out_food_x.is_null() {
        *out_food_x = fx;
    }
    if !out_food_y.is_null() {
        *out_food_y = fy;
    }
    if !out_score.is_null() {
        *out_score = game.apples_eaten;
    }
    if !out_is_dead.is_null() {
        *out_is_dead = if game.is_dead { 1 } else { 0 };
    }

    let (gen, fitness) = {
        let pop = MANAGER.ai_population.read();
        (pop.generation as c_int, pop.best_fitness)
    };

    if !out_gen.is_null() {
        *out_gen = gen;
    }
    if !out_fitness.is_null() {
        *out_fitness = fitness;
    }
    1
}

#[no_mangle]
pub unsafe extern "C" fn SnakeGetBody(out_coords: *mut c_int, max_pairs: c_int) -> c_int {
    if out_coords.is_null() || max_pairs <= 0 {
        return 0;
    }
    let game = MANAGER.active_game.read();
    let len = game.body.len().min(max_pairs as usize);
    for (i, &(x, y)) in game.body.iter().take(len).enumerate() {
        *out_coords.add(i * 2) = x;
        *out_coords.add(i * 2 + 1) = y;
    }
    len as c_int
}

#[no_mangle]
pub unsafe extern "C" fn SnakeTrainGeneration() -> c_int {
    let (w, h, walls_kill) = {
        let game = MANAGER.active_game.read();
        (game.width, game.height, game.walls_kill)
    };
    let mut pop = MANAGER.ai_population.write();
    pop.evolve_generation(w, h, walls_kill);
    pop.generation as c_int
}

#[no_mangle]
pub unsafe extern "C" fn SnakeTrainBatch(count: c_int) -> c_int {
    let (w, h, walls_kill) = {
        let game = MANAGER.active_game.read();
        (game.width, game.height, game.walls_kill)
    };
    let mut pop = MANAGER.ai_population.write();
    let num = count.clamp(1, 100);
    for _ in 0..num {
        pop.evolve_generation(w, h, walls_kill);
    }
    pop.generation as c_int
}

#[no_mangle]
pub unsafe extern "C" fn SnakeResetAi(mode: c_int) -> c_int {
    let mut pop = MANAGER.ai_population.write();
    pop.reset_ai(mode != 0); // 1 = baseline, 0 = pure random
    1
}

#[no_mangle]
pub unsafe extern "C" fn SnakeSetPopulationSize(size: c_int) -> c_int {
    let mut pop = MANAGER.ai_population.write();
    pop.set_population_size(size as usize);
    pop.agents.len() as c_int
}

#[no_mangle]
pub unsafe extern "C" fn SnakeSetMutationRate(rate: c_float) -> c_int {
    let mut pop = MANAGER.ai_population.write();
    pop.mutation_rate = rate.clamp(0.01, 0.30);
    1
}

#[no_mangle]
pub unsafe extern "C" fn SnakeExportGenome(out_buffer: *mut c_float, max_len: c_int) -> c_int {
    if out_buffer.is_null() || max_len < GENOME_SIZE as c_int {
        return 0;
    }
    let pop = MANAGER.ai_population.read();
    let slice = std::slice::from_raw_parts_mut(out_buffer, max_len as usize);
    pop.best_network.export_genome(slice) as c_int
}

#[no_mangle]
pub unsafe extern "C" fn SnakeImportGenome(in_buffer: *const c_float, len: c_int) -> c_int {
    if in_buffer.is_null() || len < GENOME_SIZE as c_int {
        return 0;
    }
    let mut pop = MANAGER.ai_population.write();
    let slice = std::slice::from_raw_parts(in_buffer, len as usize);
    if pop.best_network.import_genome(slice) {
        1
    } else {
        0
    }
}
