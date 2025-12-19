package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin("*")
public class GameController {
    @Autowired
    private PlayerRepository repo;

    @PostMapping("/win")
    public void recordWin(@RequestBody String name) {
        Player p = repo.findByName(name).orElse(new Player(name));
        p.setWins(p.getWins() + 1);
        repo.save(p);
    }

    @GetMapping("/stats")
    public List<Player> getStats() {
        return repo.findAll(Sort.by(Sort.Direction.DESC, "wins"));
    }
}