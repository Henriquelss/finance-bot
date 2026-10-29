package com.financebot.service;

import com.financebot.dto.TelegramUser;
import com.financebot.entity.User;
import com.financebot.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cria ou atualiza o usuário do Telegram associado às despesas. */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User upsert(TelegramUser telegramUser, long chatId) {
        User user = userRepository.findByTelegramUserId(telegramUser.id()).orElseGet(() -> {
            User novo = new User();
            novo.setTelegramUserId(telegramUser.id());
            return novo;
        });
        user.setTelegramChatId(chatId);
        if (telegramUser.firstName() != null && !telegramUser.firstName().isBlank()) {
            user.setDisplayName(telegramUser.firstName().length() > 100
                    ? telegramUser.firstName().substring(0, 100)
                    : telegramUser.firstName());
        }
        return userRepository.save(user);
    }
}
