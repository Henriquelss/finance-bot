package com.financebot.repository;

import com.financebot.entity.Expense;
import com.financebot.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ExpenseRepositoryTest {

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        expenseRepository.deleteAll();
        userRepository.deleteAll();

        user = new User();
        user.setTelegramUserId(1000L);
        user.setDisplayName("Teste");
        user = userRepository.save(user);
    }

    @Test
    void devePersistirDespesaComValorDecimalEDatasCorretas() {
        Expense expense = newExpense(user, new BigDecimal("100.00"), 9001L);

        Expense saved = expenseRepository.save(expense);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAmount()).isEqualByComparingTo("100.00");
        assertThat(saved.getExpenseDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo("CONFIRMED");
        assertThat(saved.getSource()).isEqualTo("TELEGRAM_AUDIO");
    }

    @Test
    void deveListarDespesasDoUsuarioNoPeriodo() {
        expenseRepository.save(newExpense(user, new BigDecimal("50.00"), 9002L));

        User outroUsuario = new User();
        outroUsuario.setTelegramUserId(2000L);
        outroUsuario = userRepository.save(outroUsuario);
        expenseRepository.save(newExpense(outroUsuario, new BigDecimal("70.00"), 9003L));

        List<Expense> resultado =
                expenseRepository.findByUserIdAndExpenseDateBetween(user.getId(),
                        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(resultado).hasSize(1);
        assertThat(resultado.getFirst().getUser().getId()).isEqualTo(user.getId());
    }

    @Test
    void deveRejeitarDespesaDuplicadaPeloMesmoTelegramUpdateId() {
        expenseRepository.save(newExpense(user, new BigDecimal("10.00"), 9004L));
        expenseRepository.flush();

        assertThatThrownBy(() -> {
            expenseRepository.save(newExpense(user, new BigDecimal("10.00"), 9004L));
            expenseRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Expense newExpense(User owner, BigDecimal amount, Long telegramUpdateId) {
        Expense expense = new Expense();
        expense.setUser(owner);
        expense.setAmount(amount);
        expense.setDescription("Despesa de teste");
        expense.setCategory("Alimentação");
        expense.setExpenseDate(LocalDate.of(2026, 10, 15));
        expense.setSource("TELEGRAM_AUDIO");
        expense.setTelegramUpdateId(telegramUpdateId);
        return expense;
    }
}
